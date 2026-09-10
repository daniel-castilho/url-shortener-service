package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Generates the DNS verification value used to prove custom-domain ownership.
 *
 * <p>Implemented by the infra adapter (SecureRandom hex with a configurable record prefix); the
 * core keeps domain logic framework-free and policy-free.
 */
public interface VerificationTokenPort {

  /** Returns a fresh DNS verification value for a new claim / re-trigger. */
  String generateToken();
}
