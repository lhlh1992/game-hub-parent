package com.gamehub.systemservice.entity.role;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * 角色权限关联表复合主键类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysRolePermissionId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID roleId;
    private UUID permissionId;
}

