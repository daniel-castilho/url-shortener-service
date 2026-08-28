package ca.tyny.urlshortener.core.service;

import ca.tyny.urlshortener.core.command.UpdateLinkCommand;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.exception.InvalidExpiryException;
import ca.tyny.urlshortener.core.exception.UrlNotFoundException;
import ca.tyny.urlshortener.core.model.CacheLookup;
import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.UtmParams;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ListUserLinksUseCase;
import ca.tyny.urlshortener.core.ports.incoming.UpdateLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort;
import ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort;
import ca.tyny.urlshortener.core.validation.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Links-as-Resource Use Cases")
class LinkUseCasesTest {

    @Mock
    private LinkQueryPort linkQueryPort;

    @Mock
    private LinkMutationPort linkMutationPort;

    @Mock
    private UrlCachePort urlCachePort;

    @Mock
    private UrlValidator urlValidator;

    private ListUserLinksUseCase listUserLinksUseCase;
    private GetLinkUseCase getLinkUseCase;
    private UpdateLinkUseCase updateLinkUseCase;
    private ArchiveLinkUseCase archiveLinkUseCase;

    private final String USER_ID = "user123";
    private final String LINK_ID = "abc123";
    private final String ORIGINAL_URL = "https://example.com";
    private final Instant EXPIRY = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() {
        listUserLinksUseCase = new ListUserLinksUseCaseImpl(linkQueryPort);
        getLinkUseCase = new GetLinkUseCaseImpl(linkQueryPort);
        updateLinkUseCase = new UpdateLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort, mock(UrlValidator.class), 31536000L);
        archiveLinkUseCase = new ArchiveLinkUseCaseImpl(linkQueryPort, linkMutationPort, urlCachePort);
    }

    private ShortUrl createShortUrl() {
        return new ShortUrl(
                LINK_ID, ORIGINAL_URL, LocalDateTime.now(), USER_ID, false, 0, null, null, null, null, null);
    }

    private ShortUrl createShortUrlWithExpiry() {
        return new ShortUrl(
                LINK_ID, ORIGINAL_URL, LocalDateTime.now(), USER_ID, false, 0, EXPIRY, null, null, null, null);
    }

    private ShortUrl createArchivedShortUrl() {
        return new ShortUrl(
                LINK_ID, ORIGINAL_URL, LocalDateTime.now(), USER_ID, false, 0, null, null, null, null, Instant.now());
    }

    @Test
    @DisplayName("ListUserLinksUseCase - returns paginated results")
    void listUserLinksReturnsPaginatedResults() {
        ShortUrl link = createShortUrl();
        var pageResult = PageResult.of(List.of(link), null);

        when(linkQueryPort.findByUserId(eq(USER_ID), eq(20), isNull()))
                .thenReturn(PageResult.of(List.of(link), null));

        PageResult<ShortUrl> result = listUserLinksUseCase.list(USER_ID, PageRequest.first(20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(LINK_ID);
        verify(linkQueryPort).findByUserId(USER_ID, 20, null);
    }

    @Test
    @DisplayName("GetLinkUseCase - returns link when owner")
    void getLinkReturnsLinkWhenOwner() {
        ShortUrl link = createShortUrl();

        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(link));

        ShortUrl result = getLinkUseCase.get(USER_ID, LINK_ID);

        assertThat(result).isEqualTo(link);
    }

    @Test
    @DisplayName("GetLinkUseCase - throws 404 when not found")
    void getLinkThrows404WhenNotFound() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> getLinkUseCase.get(USER_ID, LINK_ID))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    @DisplayName("GetLinkUseCase - throws 403 when non-owner")
    void getLinkThrows403WhenNonOwner() {
        ShortUrl link = createShortUrl();
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> getLinkUseCase.get("other-user", LINK_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("UpdateLinkUseCase - updates destination without changing code")
    void updateLinkChangesDestinationNotCode() {
        ShortUrl link = createShortUrl();
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(link));

        // We need to mock the mutation port too
        UpdateLinkUseCaseImpl impl = new UpdateLinkUseCaseImpl(
                linkQueryPort, mock(LinkMutationPort.class), mock(UrlCachePort.class), mock(UrlValidator.class), 31536000L);

        UpdateLinkCommand cmd = new UpdateLinkCommand("https://new-example.com", null, null, null, null);

        ca.tyny.urlshortener.core.model.ShortUrl updated = impl.update(USER_ID, LINK_ID, cmd);

        assertThat(updated.originalUrl()).isEqualTo("https://new-example.com");
        assertThat(updated.id()).isEqualTo(LINK_ID);
    }

    @Test
    @DisplayName("UpdateLinkUseCase - throws 403 for non-owner")
    void updateLinkThrows403ForNonOwner() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(createShortUrl()));

        assertThatThrownBy(() -> updateLinkUseCase.update("other-user", LINK_ID, new UpdateLinkCommand(null, null, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("UpdateLinkUseCase - throws 404 when not found")
    void updateLinkThrows404WhenNotFound() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateLinkUseCase.update(USER_ID, LINK_ID, new UpdateLinkCommand(null, null, null, null, null)))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    @DisplayName("UpdateLinkUseCase - throws on archived link")
    void updateLinkThrowsOnArchived() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(createArchivedShortUrl()));

        assertThatThrownBy(() -> updateLinkUseCase.update(USER_ID, LINK_ID, new UpdateLinkCommand("https://new.com", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");
    }

    @Test
    @DisplayName("ArchiveLinkUseCase - archives link")
    void archiveLinkArchivesLink() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(createShortUrl()));

        archiveLinkUseCase.archive(USER_ID, LINK_ID);

        verify(linkMutationPort).archive(LINK_ID);
    }

    @Test
    @DisplayName("ArchiveLinkUseCase - idempotent on already archived")
    void archiveLinkIdempotent() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(createArchivedShortUrl()));

        archiveLinkUseCase.archive(USER_ID, LINK_ID);

        verify(linkMutationPort, never()).archive(anyString());
    }

    @Test
    @DisplayName("ArchiveLinkUseCase - throws 403 for non-owner")
    void archiveLinkThrows403ForNonOwner() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.of(createShortUrl()));

        assertThatThrownBy(() -> archiveLinkUseCase.archive("other-user", LINK_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("ArchiveLinkUseCase - throws 404 when not found")
    void archiveLinkThrows404WhenNotFound() {
        when(linkQueryPort.findById(LINK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> archiveLinkUseCase.archive(USER_ID, LINK_ID))
                .isInstanceOf(UrlNotFoundException.class);
    }
}