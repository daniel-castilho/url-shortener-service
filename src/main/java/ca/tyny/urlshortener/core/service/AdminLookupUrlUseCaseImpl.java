package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.AdminUrlLookup;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminLookupUrlUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class AdminLookupUrlUseCaseImpl implements AdminLookupUrlUseCase {

  private final LinkQueryPort linkQueryPort;
  private final UserRepositoryPort userRepository;

  public AdminLookupUrlUseCaseImpl(LinkQueryPort linkQueryPort, UserRepositoryPort userRepository) {
    this.linkQueryPort = linkQueryPort;
    this.userRepository = userRepository;
  }

  @Override
  public AdminUrlLookup lookup(String callerRole, String code) {
    AdminListUsersUseCaseImpl.enforceAdmin(callerRole);

    ShortUrl shortUrl =
        linkQueryPort.findById(code).orElseThrow(() -> new UrlNotFoundException(code));

    String ownerEmail =
        shortUrl.userId() == null
            ? null
            : userRepository.findById(shortUrl.userId()).map(user -> user.email()).orElse(null);

    return new AdminUrlLookup(shortUrl, ownerEmail);
  }
}
