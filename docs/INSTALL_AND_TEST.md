# Installation and phone testing

## 1. One-time setup to publish the voice model (needs the repository owner)

The original model is gated on Hugging Face, and the converted package is published to your
Hugging Face account, so the conversion workflow needs your Hugging Face access token.
All steps work in a phone browser.

1. Open https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd while logged in to your
   Hugging Face account (Hshareef77) and accept the access conditions (approval is automatic).
2. Open https://huggingface.co/settings/tokens, choose **Create new token**, token type
   **Write**, name it for example `github-conversion`, create it and copy it.
3. Open https://github.com/hshareef77/dhivehi-offline-TTS/settings/secrets/actions, choose
   **New repository secret**, name `HF_TOKEN`, paste the token, **Add secret**.
4. Open the **Actions** tab, workflow **Convert voice model**, open the most recent run and
   choose **Re-run jobs** (or push any change under `conversion/`).

The run takes about one to two hours. It downloads the 6.6 GB original once on GitHub's
computer, converts it, checks it against the original model, generates Dhivehi speech with
all five voices, measures intelligibility with a Dhivehi speech recogniser, checks the Kotlin
engine on the real model, and uploads the verified package to
`https://huggingface.co/Hshareef77/csm-1b-dhivehi-5spk-onnx`. The run's **voice-model-report**
artifact contains the sample WAV files and the quality report.

Your phone never downloads the original 6.6 GB model.

## 2. Installing the app

1. Open the **Actions** tab, workflow **Android APK**, the newest successful run, and download
   the artifact **dhivehi-offline-tts-apk** (a zip containing the APK). Extract and install it.
2. Builds from this version on are signed with a fixed key, so later APKs install over the
   previous one without uninstalling. If an older prototype build is installed, Android may
   refuse the first update because that build used a different, random key; uninstall the old
   prototype once in that case.

## 3. Phone test list (Samsung Galaxy S23 FE)

Please report the result of each item.

1. **Start and navigation**: TalkBack and Jieshuo+ read every heading, button, the text field
   and the five voice options in a sensible order.
2. **Download**: choose *Download voice model*. The dialog states the size (about 1.5 GB
   expected) and free storage. Confirm. Progress is announced every 10 percent. Leave the app
   and come back: the download continues.
3. **Cancel and resume**: during the download choose *Cancel download*, then *Download voice
   model* again: it should continue from where it stopped, not restart.
4. **Install check**: after download the screen says *Voice model installed, version N*.
5. **Offline**: switch on airplane mode for all following tests.
6. **Short speech**: type or paste a Dhivehi sentence, choose *Female voice 1*, *Generate speech*.
   Note: time until *Speech ready*, whether the speech is understandable.
7. **All five voices**: repeat with each voice.
8. **Playback**: Play, Pause, Play (resumes), Back 10 seconds, Forward 10 seconds, the position
   slider, speed slider (0.5× to 2.0×), Stop.
9. **Long text**: import a TXT file of several paragraphs. Generation proceeds part by part
   with announcements. Try *Cancel speech generation* in the middle.
10. **Export**: *Export recording as WAV file*, save to Downloads, and play it in another app.
11. **Saved recordings**: close and reopen the app; open and delete a saved recording.
12. **Stability**: generate several times in a row; report any crash, freezing, phone heating,
    or the message about insufficient memory.
13. **Delete model**: *Delete voice model*, confirm, check storage is freed.

Useful numbers to report: download time, time to generate one sentence (seconds) and the
length of the produced speech, and whether the phone becomes hot.
