package com.billforge.auth.mapper;

import com.billforge.auth.dto.role.RoleResponse;
import com.billforge.auth.entity.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoleMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "permissionCount", ignore = true)
    RoleResponse toResponse(Role entity);

    default RoleResponse toResponseWithPermissions(Role entity, int permissionCount) {
        RoleResponse response = toResponse(entity);
        response.setPermissionCount(permissionCount);
        return response;
    }
}
