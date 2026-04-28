package com.pulse.admin;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${pulse.admin.email:admin@pulse.local}") String email,
                          @Value("${pulse.admin.password:admin12345}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedAdmin() {
        if (users.findByEmail(email).isEmpty()) {
            users.save(new User(email, encoder.encode(password), Role.ADMIN));
        }
    }
}
