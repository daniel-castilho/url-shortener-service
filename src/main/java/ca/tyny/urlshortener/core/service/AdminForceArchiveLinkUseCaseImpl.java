package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminForceArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;

public class AdminForceArchiveLinkUseCaseImpl implements AdminForceArchiveLinkUseCase {

  private final LinkQueryPort linkQueryPort;
  private final LinkMutationPort linkMutationPort;
  private final UrlCachePort urlCachePort;

  public AdminForceArchiveLinkUseCaseImpl(
      LinkQueryPort linkQueryPort, LinkMutationPort linkMutationPort, UrlCachePort urlCachePort) {
    this.linkQueryPort = linkQueryPort;
    this.linkMutationPort = linkMutationPort;
    this.urlCachePort = urlCachePort;
  }

  @Override
  public void forceArchiveLink(String callerRole, String id) {
    AdminListUsersUseCaseImpl.enforceAdmin(callerRole);

    ShortUrl shortUrl = linkQueryPort.findById(id).orElseThrow(() -> new UrlNotFoundException(id));

    if (shortUrl.deletedAt() != null) {
      return; // idempotent — already archived
    }

    linkMutationPort.archive(id);
    urlCachePort.evict(id);
  }
}
