package bridge.protocol;

/**
 * What a 3DS says about itself in its login batch, verified from a capture:
 * {@code {"playerName":"…","skinId":"Standard_Steve","uuid":"…"}} — 103 bytes, no skin pixels.
 * The skin is an id the receiving console looks up in its own built-in list; the uuid is a stable
 * per-console identity.
 */
public record Login(String name, String skinId, String uuid) {}
