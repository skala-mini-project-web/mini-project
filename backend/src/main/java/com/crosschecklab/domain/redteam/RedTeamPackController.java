package com.crosschecklab.domain.redteam;

import com.crosschecklab.domain.redteam.dto.RedTeamPackResponse;
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

@Tag(name = "RedTeamPack", description = "위험 점검 Pack")
@RestController
@RequestMapping("/api/red-team-packs")
@RequiredArgsConstructor
public class RedTeamPackController {

    private final RedTeamPackService redTeamPackService;

    @GetMapping
    @Operation(summary = "TEST-002 Red Team Pack 목록",
            description = "Pack 과 소속 규칙을 함께 반환한다. active 는 선택 필터다.")
    public ResponseEntity<ListResponse<RedTeamPackResponse>> findAll(
            @RequestParam(required = false) Boolean active,
            @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(redTeamPackService.findAll(active));
    }
}
