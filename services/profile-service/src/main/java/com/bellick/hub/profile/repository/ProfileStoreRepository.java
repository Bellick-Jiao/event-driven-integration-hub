package com.bellick.hub.profile.repository;

import com.bellick.hub.profile.model.ProfileStore;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileStoreRepository extends JpaRepository<ProfileStore, String> {
}
