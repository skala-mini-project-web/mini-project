package com.crosschecklab.domain.user;

import com.crosschecklab.domain.user.dto.DemoSessionRequest;
import com.crosschecklab.domain.user.dto.DemoSessionResponse;
import com.crosschecklab.domain.user.dto.DemoUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "데모 인증")
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/users")
    @Operation(summary = "데모 사용자 목록 조회",
            description = "세션 생성 전에 선택할 수 있는 활성 데모 사용자를 ID 순으로 반환한다.")
    public ResponseEntity<List<DemoUserResponse>> getDemoUsers() {
        return ResponseEntity.ok(userService.getDemoUsers());
    }

    @PostMapping("/session")
    @Operation(summary = "AUTH-001 데모 정체성 검증",
            description = "userId 와 role 이 DB 와 일치하는지 확인한다. 토큰이나 세션은 발급하지 않는다.")
    public ResponseEntity<DemoSessionResponse> createDemoSession(@Valid @RequestBody DemoSessionRequest request) {
        return ResponseEntity.ok(userService.verifyDemoSession(request));
    }
}
