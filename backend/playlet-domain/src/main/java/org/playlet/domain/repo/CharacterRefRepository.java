package org.playlet.domain.repo;

import org.playlet.domain.entity.CharacterRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterRefRepository extends JpaRepository<CharacterRef, Long> {
    List<CharacterRef> findByProject_Id(Long projectId);
}
