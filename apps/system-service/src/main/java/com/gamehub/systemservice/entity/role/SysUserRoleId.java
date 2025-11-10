package com.gamehub.systemservice.entity.role;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * 用户角色关联表复合主键类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysUserRoleId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID userId;
    private UUID roleId;
}

