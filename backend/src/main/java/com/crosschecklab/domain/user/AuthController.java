package com.crosschecklab.domain.user;

import com.crosschecklab.domain.user.dto.DemoSessionRequest;
import com.crosschecklab.domain.user.dto.DemoSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/session")
    @Operation(summary = "AUTH-001 데모 정체성 검증",
            description = "userId 와 role 이 DB 와 일치하는지 확인한다. 토큰이나 세션은 발급하지 않는다.")
    public ResponseEntity<DemoSessionResponse> createDemoSession(@Valid @RequestBody DemoSessionRequest request) {
        return ResponseEntity.ok(userService.verifyDemoSession(request));
    }
}
