# NEXORA Skin Atlas v1

Status: format contract for the NEXORA Android client. This document defines an original NEXORA texture layout; it does not imply compatibility with Minecraft skin rendering.

## File constraints

- PNG only.
- Exactly 64 × 64 pixels.
- RGBA is recommended; alpha is preserved for transparent pixels.
- Maximum encoded file size: 256 KiB.
- Pixel coordinates are integer coordinates from the top-left corner.
- Rectangles below use half-open bounds: `[x0, y0, x1, y1)`.
- UV coordinates are normalized by dividing pixel coordinates by 64.
- The v coordinate is measured from the top of the PNG; the renderer must account for OpenGL's texture-coordinate orientation when uploading the bitmap.

## Base body UV layout

The atlas uses the classic 64 × 64 cuboid net as a coordinate convention, with NEXORA's own model dimensions and rendering implementation.

| Part | Face | Pixel rectangle |
|---|---|---|
| Head (8 × 8 × 8) | right | [0, 8, 8, 16) |
| Head | front | [8, 8, 16, 16) |
| Head | left | [16, 8, 24, 16) |
| Head | back | [24, 8, 32, 16) |
| Head | top | [8, 0, 16, 8) |
| Head | bottom | [16, 0, 24, 8) |
| Torso (8 × 12 × 4) | right | [16, 20, 20, 32) |
| Torso | front | [20, 20, 28, 32) |
| Torso | left | [28, 20, 32, 32) |
| Torso | back | [32, 20, 40, 32) |
| Torso | top | [20, 16, 28, 20) |
| Torso | bottom | [28, 16, 36, 20) |
| Right leg (4 × 12 × 4) | right | [0, 20, 4, 32) |
| Right leg | front | [4, 20, 8, 32) |
| Right leg | left | [8, 20, 12, 32) |
| Right leg | back | [12, 20, 16, 32) |
| Right leg | top | [4, 16, 8, 20) |
| Right leg | bottom | [8, 16, 12, 20) |
| Right arm (4 × 12 × 4) | right | [40, 20, 44, 32) |
| Right arm | front | [44, 20, 48, 32) |
| Right arm | left | [48, 20, 52, 32) |
| Right arm | back | [52, 20, 56, 32) |
| Right arm | top | [44, 16, 48, 20) |
| Right arm | bottom | [48, 16, 52, 20) |
| Left leg (4 × 12 × 4) | right | [16, 52, 20, 64) |
| Left leg | front | [20, 52, 24, 64) |
| Left leg | left | [24, 52, 28, 64) |
| Left leg | back | [28, 52, 32, 64) |
| Left leg | top | [20, 48, 24, 52) |
| Left leg | bottom | [24, 48, 28, 52) |
| Left arm (4 × 12 × 4) | right | [32, 52, 36, 64) |
| Left arm | front | [36, 52, 40, 64) |
| Left arm | left | [40, 52, 44, 64) |
| Left arm | back | [44, 52, 48, 64) |
| Left arm | top | [36, 48, 40, 52) |
| Left arm | bottom | [40, 48, 44, 52) |

## Rendering requirements

1. Sample each cuboid face from its own rectangle; do not stretch one pixel region across every face.
2. Keep the face order explicit in code (front, back, left, right, top, bottom) so UV orientation can be tested and corrected independently.
3. Use nearest-neighbor sampling for the pixel-art look.
4. Preserve alpha and enable blending only for the skin pass. Opaque pixels must still write depth.
5. The outer-layer/overlay regions are reserved for a later atlas revision; v1 renders only the base layer.
6. A client must not substitute one player's local PNG for another player's texture. A remote player's custom skin must be received and validated for that player's session before it is rendered.
7. Until custom-skin transfer is implemented, the existing five built-in appearance presets remain the network fallback.

## Security and resource limits

- Revalidate dimensions, PNG signature, decode success, and byte limit on both client import and server receipt.
- Do not trust a filename, MIME type, or client-provided dimensions.
- Keep decoded bitmaps bounded to 64 × 64 and recycle temporary decode buffers after texture upload.
- Reject malformed or oversized skin payloads before allocating a full transfer buffer.
