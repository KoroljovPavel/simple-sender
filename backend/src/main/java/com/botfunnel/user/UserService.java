package com.botfunnel.user;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Mono<User> save(User user) {
        return userRepository.save(user);
    }

    public Mono<User> softDelete(String userId) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    user.setStatus(UserStatus.deleted);
                    user.setDeletedAt(Instant.now());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                });
    }

    public boolean canActivate(User user) {
        return user.getStatus() != UserStatus.blocked;
    }
}
