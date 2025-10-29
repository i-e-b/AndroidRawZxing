# AndroidRawZxing

A powerful code scanner that can read poorly printed or damaged codes with relatively high success rate.

## Thresholding

The core improvement is a new thresholding method based on a fast unsharp mask, with adaptive feature size and exposure compensation.
By scanning through a range of recovery options, we get a slower overall capture rate, but a much higher chance to get a capture.

This is best for manual capture of codes by human operators in non-ideal conditions.

## Implementation

Feeding ZXing QR code reader from low-level Android Camera2 API

Contains a snapshot of the ZXing core in `app/src/main/java/com/google/zxing`
and a simplified _Camera2_ controller feeding into the core in `app/src/main/java/com/ieb/zxingtest`.

Uses nothing beyond the most basic Android APIs, and should work with any app using API 26 or up.

