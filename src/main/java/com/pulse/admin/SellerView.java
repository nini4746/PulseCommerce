package com.pulse.admin;

import com.pulse.domain.User;

public record SellerView(Long id, String email, boolean suspended) {
    public static SellerView of(User u) {
        return new SellerView(u.getId(), u.getEmail(), u.isSuspended());
    }
}
