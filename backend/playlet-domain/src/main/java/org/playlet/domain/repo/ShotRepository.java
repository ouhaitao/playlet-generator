package org.playlet.domain.repo;

import org.playlet.domain.entity.Shot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShotRepository extends JpaRepository<Shot, Long> {
    List<Shot> findByProjectIdOrderByOrdAsc(Long projectId);
}
