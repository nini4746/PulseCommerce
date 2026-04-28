package com.pulse.security;

import com.pulse.domain.Role;

public record AuthPrincipal(Long userId, String email, Role role) {}
