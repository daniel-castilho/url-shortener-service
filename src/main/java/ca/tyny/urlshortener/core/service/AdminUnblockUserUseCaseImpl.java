package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.UserNotFoundException;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminUnblockUserUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class AdminUnblockUserUseCaseImpl implements AdminUnblockUserUseCase {

  private final UserRepositoryPort userRepository;

  public AdminUnblockUserUseCaseImpl(UserRepositoryPort userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public void unblock(String callerEmail, String callerRole, String userId) {
    AdminListUsersUseCaseImpl.enforceAdmin(callerRole);

    userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));

    userRepository.setBlocked(userId, false);
  }
}
