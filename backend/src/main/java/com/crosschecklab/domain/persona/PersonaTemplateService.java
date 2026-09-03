package com.crosschecklab.domain.persona;

import com.crosschecklab.domain.persona.dto.PersonaTemplateResponse;
import com.crosschecklab.global.common.ListResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonaTemplateService {

    private final PersonaTemplateRepository personaTemplateRepository;

    // TEST-001. active 는 선택 필터이며 null 이면 전체를 반환한다.
    public ListResponse<PersonaTemplateResponse> findAll(Boolean active) {
        List<PersonaTemplate> templates = active == null
                ? personaTemplateRepository.findAllByOrderByIdAsc()
                : personaTemplateRepository.findAllByActiveOrderByIdAsc(active);
        return ListResponse.of(templates, PersonaTemplateResponse::from);
    }
}
