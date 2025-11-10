package com.gamehub.systemservice.repository.user;

import com.gamehub.systemservice.entity.user.SysUserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 用户扩展信息 Repository
 */
@Repository
public interface SysUserProfileRepository extends JpaRepository<SysUserProfile, UUID> {
}

