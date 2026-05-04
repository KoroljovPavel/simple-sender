package com.botfunnel.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    UserService userService;

    @Test
    void softDelete_setsStatusDeletedAndDeletedAt() {
        User user = new User();
        user.setId("user-1");
        user.setStatus(UserStatus.active);

        when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        User result = userService.softDelete("user-1").block();

        assertThat(result.getStatus()).isEqualTo(UserStatus.deleted);
        assertThat(result.getDeletedAt()).isNotNull();
    }

    @Test
    void canActivate_blockedUser_returnsFalse() {
        User user = new User();
        user.setStatus(UserStatus.blocked);

        assertThat(userService.canActivate(user)).isFalse();
    }
}
