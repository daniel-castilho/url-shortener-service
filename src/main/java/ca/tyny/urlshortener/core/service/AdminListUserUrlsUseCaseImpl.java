package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.UserNotFoundException;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUserUrlsUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class AdminListUserUrlsUseCaseImpl implements AdminListUserUrlsUseCase {

  private final UserRepositoryPort userRepository;
  private final LinkQueryPort linkQueryPort;

  public AdminListUserUrlsUseCaseImpl(
      UserRepositoryPort userRepository, LinkQueryPort linkQueryPort) {
    this.userRepository = userRepository;
    this.linkQueryPort = linkQueryPort;
  }

  @Override
  public PageResult<ShortUrl> listUserUrls(String callerRole, String userId, PageRequest request) {
    AdminListUsersUseCaseImpl.enforceAdmin(callerRole);

    userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));

    return linkQueryPort.findByUserId(userId, request.limit(), request.cursor());
  }
}
