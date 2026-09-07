#!/usr/bin/env python3
"""schema-only pg_dump DDL을 dbdiagram DBML로 변환한다.

- 입력: docs/deliverables/05-database/ARGUS-current-schema.sql (pg_dump --schema-only)
- 출력: docs/deliverables/04-erd/ARGUS-ERD.dbml
- 기존 DBML의 TableGroup 구성과 Table Note는 유지한다.
- 컬럼·타입·NOT NULL·default·identity·PK·UNIQUE·FK·index는 SQL만을 기준으로 다시 쓴다.
- flyway_schema_history는 제외한다.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SQL = ROOT / "docs/deliverables/05-database/ARGUS-current-schema.sql"
DEFAULT_DBML = ROOT / "docs/deliverables/04-erd/ARGUS-ERD.dbml"
EXCLUDED_TABLES = {"flyway_schema_history"}

TYPE_MAP = {
    "timestamp with time zone": "timestamptz",
    "timestamp without time zone": "timestamp",
    "double precision": "double",
    "character varying": "varchar",
    "character": "char",
}


def map_type(pg_type: str) -> str:
    pg_type = pg_type.replace("public.", "")
    match = re.fullmatch(r"([a-z ]+?)(\(.*\))?", pg_type)
    base, size = match.group(1).strip(), match.group(2) or ""
    return TYPE_MAP.get(base, base) + size


def parse_tables(sql: str) -> dict[str, list[dict]]:
    tables: dict[str, list[dict]] = {}
    for match in re.finditer(r"CREATE TABLE public\.(\w+) \((.*?)\n\);", sql, re.S):
        name = match.group(1)
        if name in EXCLUDED_TABLES:
            continue
        columns = []
        for raw in match.group(2).split("\n"):
            line = raw.strip().rstrip(",")
            if not line or line.startswith("CONSTRAINT"):
                continue
            column, rest = line.split(" ", 1)
            not_null = " NOT NULL" in rest
            default = None
            default_match = re.search(r" DEFAULT (.+?)(?: NOT NULL)?$", rest)
            if default_match:
                default = default_match.group(1)
            pg_type = re.sub(r" DEFAULT .*$", "", rest).replace(" NOT NULL", "").strip()
            columns.append(
                {
                    "name": column,
                    "type": map_type(pg_type),
                    "not_null": not_null,
                    "default": default,
                    "identity": False,
                }
            )
        tables[name] = columns
    for match in re.finditer(
        r"ALTER TABLE public\.(\w+) ALTER COLUMN (\w+) ADD GENERATED", sql
    ):
        table, column = match.group(1), match.group(2)
        if table in tables:
            for col in tables[table]:
                if col["name"] == column:
                    col["identity"] = True
    return tables


def parse_constraints(sql: str):
    primary: dict[str, list[str]] = {}
    unique: dict[str, list[tuple[str, list[str]]]] = {}
    checks: dict[str, list[str]] = {}
    for match in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) (PRIMARY KEY|UNIQUE|CHECK) (\(.*?\));",
        sql,
        re.S,
    ):
        table, name, kind, body = match.groups()
        if kind == "CHECK":
            checks.setdefault(table, []).append(" ".join(body.split()))
            continue
        columns = [c.strip() for c in body.strip("()").split(",")]
        if kind == "PRIMARY KEY":
            primary[table] = columns
        else:
            unique.setdefault(table, []).append((name, columns))
    return primary, unique, checks


def parse_indexes(sql: str) -> dict[str, list[tuple[str, list[str], bool, str]]]:
    indexes: dict[str, list[tuple[str, list[str], bool, str]]] = {}
    for match in re.finditer(
        r"CREATE (UNIQUE )?INDEX (\w+) ON public\.(\w+) USING (\w+) \((.*?)\)(?: WHERE (.*?))?;",
        sql,
    ):
        is_unique, name, table, method, body, where = match.groups()
        raw_columns = [c.strip() for c in body.split(",")]
        columns = [c.split(" ")[0] for c in raw_columns]
        qualifiers = [c for c in raw_columns if " " in c]
        note = method if method != "btree" else ""
        if qualifiers:
            note = (note + " " if note else "") + ", ".join(q.replace("public.", "") for q in qualifiers)
        if where:
            note = (note + " " if note else "") + f"where {clean_predicate(where)}"
        indexes.setdefault(table, []).append((name, columns, bool(is_unique), note))
    return indexes


def parse_foreign_keys(sql: str) -> list[tuple[str, str, str, str, str]]:
    refs = []
    for match in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ FOREIGN KEY \(([\w, ]+)\) REFERENCES public\.(\w+)\(([\w, ]+)\)(?: ON DELETE (\w+(?: \w+)?))?(?: DEFERRABLE INITIALLY DEFERRED)?;",
        sql,
    ):
        table, columns, ref_table, ref_columns, on_delete = match.groups()
        refs.append((table, columns.split(", "), ref_table, ref_columns.split(", "), on_delete))
    return refs


def ref_target(table: str, columns: list[str]) -> str:
    if len(columns) == 1:
        return f"{table}.{columns[0]}"
    return f"{table}.({', '.join(columns)})"


def clean_predicate(where: str) -> str:
    where = re.sub(r"::[a-z_ ]+(\[\])?", "", where)
    where = where.replace("'", '"')
    for _ in range(3):
        where = re.sub(r'\((\w+|"[^"]*"|ARRAY\[[^\]]*\])\)', r"\1", where)
    where = re.sub(r"^\((.*)\)$", r"\1", where)
    where = re.sub(r"(\w)([=<>]+)", r"\1 \2", where)
    return " ".join(where.split())


def parse_existing_dbml(text: str):
    groups: list[tuple[str, list[str]]] = []
    for match in re.finditer(r"^TableGroup (\w+) \{(.*?)^\}", text, re.S | re.M):
        members = [line.strip() for line in match.group(2).split("\n") if line.strip()]
        groups.append((match.group(1), members))
    notes: dict[str, str] = {}
    for match in re.finditer(r"^Table (\w+) \{(.*?)^\}", text, re.S | re.M):
        note = re.search(r"^\s*Note: '(.*)'\s*$", match.group(2), re.M)
        if note:
            notes[match.group(1)] = note.group(1)
    return groups, notes


def render(sql_text: str, existing: str, sql_rel: str) -> str:
    tables = parse_tables(sql_text)
    primary, unique, checks = parse_constraints(sql_text)
    indexes = parse_indexes(sql_text)
    refs = parse_foreign_keys(sql_text)
    groups, notes = parse_existing_dbml(existing)

    grouped = {member for _, members in groups for member in members}
    ungrouped = sorted(set(tables) - grouped)
    stale = sorted(grouped - set(tables))
    if stale:
        raise SystemExit(f"TableGroup에 있으나 schema에 없는 table: {stale}")

    out = [
        "// ARGUS logical ERD",
        f"// Generated by tools/generate_erd_dbml.py from {sql_rel}",
        "// Source: running PostgreSQL + pgvector schema-only pg_dump (Flyway V1–V24)",
        "// Paste this entire file into https://dbdiagram.io/d/6a980de65450bea1becc5dd7",
        f"// Excluded: {', '.join(sorted(EXCLUDED_TABLES))}",
        "",
    ]
    for name, members in groups:
        out.append(f"TableGroup {name} {{")
        out.extend(f"  {member}" for member in members if member in tables)
        out.append("}")
        out.append("")
    if ungrouped:
        out.append("TableGroup ungrouped {")
        out.extend(f"  {member}" for member in ungrouped)
        out.append("}")
        out.append("")

    order = [m for _, members in groups for m in members if m in tables] + ungrouped
    for table in order:
        columns = tables[table]
        pk = primary.get(table, [])
        out.append(f"Table {table} {{")
        for col in columns:
            settings = []
            if len(pk) == 1 and pk[0] == col["name"]:
                settings.append("pk")
            if col["identity"]:
                settings.append("increment")
            if col["not_null"] and "pk" not in settings:
                settings.append("not null")
            if col["default"] is not None:
                default = col["default"]
                if default in {"true", "false"} or re.fullmatch(r"-?\d+(\.\d+)?", default):
                    settings.append(f"default: {default}")
                elif default.startswith("'"):
                    literal = re.sub(r"::[\w ]+(\(\d+\))?$", "", default)
                    settings.append(f"default: {literal}")
                else:
                    settings.append(f"default: `{default}`")
            suffix = f" [{', '.join(settings)}]" if settings else ""
            out.append(f"  {col['name']} {col['type']}{suffix}")
        index_lines = []
        if len(pk) > 1:
            index_lines.append(f"    ({', '.join(pk)}) [pk]")
        for name, cols in unique.get(table, []):
            target = cols[0] if len(cols) == 1 else f"({', '.join(cols)})"
            index_lines.append(f"    {target} [unique, name: '{name}']")
        for name, cols, is_unique, note in indexes.get(table, []):
            target = cols[0] if len(cols) == 1 else f"({', '.join(cols)})"
            settings = ["unique"] if is_unique else []
            settings.append(f"name: '{name}'")
            if note:
                settings.append(f"note: '{note}'")
            index_lines.append(f"    {target} [{', '.join(settings)}]")
        if index_lines:
            out.append("")
            out.append("  Indexes {")
            out.extend(index_lines)
            out.append("  }")
        note_parts = []
        if table in notes:
            note_parts.append(notes[table])
        for check in checks.get(table, []):
            note_parts.append(f"CHECK {check}")
        if note_parts:
            out.append("")
            out.append(f"  Note: '{' / '.join(note_parts)}'")
        out.append("}")
        out.append("")

    out.append("// Foreign keys")
    for table, columns, ref_table, ref_columns, on_delete in refs:
        if table in EXCLUDED_TABLES or ref_table in EXCLUDED_TABLES:
            continue
        setting = f" [delete: {on_delete.lower()}]" if on_delete else ""
        out.append(f"Ref: {ref_target(table, columns)} > {ref_target(ref_table, ref_columns)}{setting}")
    out.append("")
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sql", type=Path, default=DEFAULT_SQL)
    parser.add_argument("--dbml", type=Path, default=DEFAULT_DBML)
    parser.add_argument("--check", action="store_true", help="파일을 쓰지 않고 현재 DBML과 비교만 한다")
    args = parser.parse_args()

    existing = args.dbml.read_text(encoding="utf-8")
    rendered = render(
        args.sql.read_text(encoding="utf-8"),
        existing,
        str(args.sql.relative_to(ROOT)) if args.sql.is_relative_to(ROOT) else str(args.sql),
    )
    if args.check:
        if rendered != existing:
            raise SystemExit("DBML이 schema SQL과 다르다. tools/generate_erd_dbml.py 를 실행해 갱신하라.")
        print("DBML is in sync with schema SQL")
        return
    args.dbml.write_text(rendered, encoding="utf-8")
    table_count = len(parse_tables(args.sql.read_text(encoding="utf-8")))
    print(f"Wrote {args.dbml} ({table_count} tables)")


if __name__ == "__main__":
    main()
