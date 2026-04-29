package com.pulse.admin;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${pulse.admin.email:admin@pulse.local}") String email,
                          @Value("${pulse.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedAdmin() {
        if (password == null || password.isBlank()) {
            log.warn("ADMIN_PASSWORD not set; skipping admin bootstrap (no admin user will exist)");
            return;
        }
        if (users.findByEmail(email).isEmpty()) {
            users.save(new User(email, encoder.encode(password), Role.ADMIN));
            log.info("seeded admin user email={}", email);
        }
    }
}
