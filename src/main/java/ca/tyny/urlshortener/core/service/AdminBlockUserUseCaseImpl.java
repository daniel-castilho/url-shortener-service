package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.UserNotFoundException;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminBlockUserUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class AdminBlockUserUseCaseImpl implements AdminBlockUserUseCase {

  private final UserRepositoryPort userRepository;

  public AdminBlockUserUseCaseImpl(UserRepositoryPort userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public void block(String callerEmail, String callerRole, String userId) {
    AdminListUsersUseCaseImpl.enforceAdmin(callerRole);

    User target =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found"));

    User caller =
        userRepository
            .findByEmail(callerEmail)
            .orElseThrow(() -> new UserNotFoundException("User not found"));

    if (caller.id().equals(target.id())) {
      throw new IllegalArgumentException("You cannot block your own account");
    }

    userRepository.setBlocked(userId, true);
  }
}
