package org.playlet.domain.repo;

import org.playlet.domain.entity.ShotVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShotVersionRepository extends JpaRepository<ShotVersion, Long> {
    List<ShotVersion> findByShot_IdOrderByVersionNoAsc(Long shotId);
}
