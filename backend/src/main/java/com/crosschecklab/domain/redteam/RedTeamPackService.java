package com.crosschecklab.domain.redteam;

import com.crosschecklab.domain.redteam.dto.RedTeamPackResponse;
import com.crosschecklab.global.common.ListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RedTeamPackService {

    private final RedTeamPackRepository redTeamPackRepository;

    // TEST-002. active 는 선택 필터이며 null 이면 전체를 반환한다.
    public ListResponse<RedTeamPackResponse> findAll(Boolean active) {
        return ListResponse.of(redTeamPackRepository.findAllWithRules(active), RedTeamPackResponse::from);
    }
}
