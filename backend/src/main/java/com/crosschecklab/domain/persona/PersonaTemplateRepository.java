package com.crosschecklab.domain.persona;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonaTemplateRepository extends JpaRepository<PersonaTemplate, Long> {

    // 시드 id 순서가 곧 화면 노출 순서다.
    List<PersonaTemplate> findAllByOrderByIdAsc();

    List<PersonaTemplate> findAllByActiveOrderByIdAsc(boolean active);
}
