package com.botfunnel.user;

import com.botfunnel.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.softDelete("user-1");

        assertThat(result.getStatus()).isEqualTo(UserStatus.deleted);
        assertThat(result.getDeletedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void softDelete_userNotFound_throwsAppException() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.softDelete("missing"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void canActivate_blockedUser_returnsFalse() {
        User user = new User();
        user.setStatus(UserStatus.blocked);

        assertThat(userService.canActivate(user)).isFalse();
    }

    @Test
    void canActivate_activeUser_returnsTrue() {
        User user = new User();
        user.setStatus(UserStatus.active);

        assertThat(userService.canActivate(user)).isTrue();
    }

    @Test
    void canActivate_pendingUser_returnsTrue() {
        User user = new User();
        user.setStatus(UserStatus.pending);

        assertThat(userService.canActivate(user)).isTrue();
    }
}
