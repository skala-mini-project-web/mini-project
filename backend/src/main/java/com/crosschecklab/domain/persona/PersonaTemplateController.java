package com.crosschecklab.domain.persona;

import com.crosschecklab.domain.persona.dto.PersonaTemplateResponse;
import com.crosschecklab.global.common.ListResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "PersonaTemplate", description = "AI 소비자 Persona")
@RestController
@RequestMapping("/api/persona-templates")
@RequiredArgsConstructor
public class PersonaTemplateController {

    private final PersonaTemplateService personaTemplateService;

    @GetMapping
    @Operation(summary = "TEST-001 Persona 템플릿 목록",
            description = "고정 Persona 5종을 시드 id 순서로 반환한다. active 는 선택 필터다.")
    public ResponseEntity<ListResponse<PersonaTemplateResponse>> findAll(
            @RequestParam(required = false) Boolean active,
            @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(personaTemplateService.findAll(active));
    }
}
