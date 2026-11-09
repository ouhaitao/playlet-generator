package org.playlet.domain.repo;

import org.playlet.domain.entity.PromptDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptDraftRepository extends JpaRepository<PromptDraft, Long> {
    List<PromptDraft> findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(String ownerType, Long ownerId);

    Optional<PromptDraft> findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
            String ownerType, Long ownerId, String fieldName);
}
