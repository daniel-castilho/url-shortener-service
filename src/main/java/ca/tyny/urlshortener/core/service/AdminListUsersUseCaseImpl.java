package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.model.UserAdminItem;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUsersUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.AdminEmailPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

public class AdminListUsersUseCaseImpl implements AdminListUsersUseCase {

  private static final String ADMIN_ROLE = "ADMIN";

  private final UserRepositoryPort userRepository;
  private final AdminEmailPort adminEmailPort;

  public AdminListUsersUseCaseImpl(
      UserRepositoryPort userRepository, AdminEmailPort adminEmailPort) {
    this.userRepository = userRepository;
    this.adminEmailPort = adminEmailPort;
  }

  @Override
  public PageResult<UserAdminItem> list(
      String callerEmail, String callerRole, String emailPrefix, PageRequest request) {
    enforceAdmin(callerRole);

    int limit = request.limit();
    PageResult<User> page =
        (emailPrefix == null || emailPrefix.isBlank())
            ? userRepository.findPage(request.cursor(), limit)
            : userRepository.findPageByEmailPrefix(emailPrefix, request.cursor(), limit);

    java.util.List<UserAdminItem> items =
        page.items().stream()
            .map(
                user ->
                    new UserAdminItem(
                        user.id(),
                        user.email(),
                        user.name(),
                        adminEmailPort.isAdminEmail(user.email()) ? ADMIN_ROLE : "USER",
                        user.blocked(),
                        user.createdAt()))
            .toList();
    return new PageResult<>(items, page.nextCursor(), page.hasMore());
  }

  static void enforceAdmin(String callerRole) {
    if (!ADMIN_ROLE.equals(callerRole)) {
      throw new ForbiddenException("Forbidden");
    }
  }
}
