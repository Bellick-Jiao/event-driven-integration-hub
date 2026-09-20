package com.bellick.hub.profile.repository;

import com.bellick.hub.profile.model.DlqEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DlqEventRepository extends JpaRepository<DlqEvent, UUID> {

    List<DlqEvent> findAllByOrderByCreatedAtDesc();
}
