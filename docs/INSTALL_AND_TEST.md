# Install and test OmniVoice

1. Install **Dhivehi-Offline-TTS-OmniVoice.apk** from the validated OmniVoice prerelease.
   It uses the existing package name and stable signing key; do not uninstall to update.
2. In the app choose **Download voice model**. The dialog states the exact total download
   and free storage. The model is separate from the APK, approximately 1.37 GB.
3. Download over Wi-Fi. Leaving the screen does not stop the download. Cancel and resume
   are supported. Every model file must pass SHA-256 verification before installation.
   The old CSM package is replaced after the new package is safely installed.
4. Turn on airplane mode for the following tests.
5. Select **Automatic voice**, enter a short Dhivehi sentence, then **Generate speech**.
   Play and judge pronunciation. Report the displayed audio duration and generation time.
6. To clone a voice, enter its exact spoken words under **Exact words spoken in the reference
   recording**, then choose **Import WAV and save reference voice**. Use a clear 3–10 second
   WAV containing one speaker, without music. This version supports PCM16/float WAV only.
   After it is saved, select **Saved reference voice** and generate another short sentence.
7. Try a paragraph. Generation reports part count and diffusion steps. Completed parts can
   play while later parts are generated. Cancellation keeps completed sections in recordings.
8. Test playback, seeking, speed, WAV export, reopening saved recordings and deleting them.
9. Check that TalkBack/Jieshuo+ announce labels, selected voice and progress without losing
   focus. Check that cancellation works and the app survives switching to another app.
10. Report missing/repeated words, voice consistency, phone heating, crashes or slow output.
    Use **Share error details** after a failure.

This release is a phone test build. Hosted-demo speed does not establish phone speed.
The voice model licence is non-commercial; see THIRD_PARTY_NOTICES.md.
