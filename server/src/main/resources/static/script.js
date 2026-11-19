const startButton = document.getElementById("startButton");
const stopButton = document.getElementById("stopButton");
const statusText = document.getElementById("status");
const transcriptionText = document.getElementById("transcription");

let mediaRecorder = null;
let mediaStream = null;
let audioChunks = [];

startButton.addEventListener("click", async () => {

    try {

        statusText.textContent = "Requesting microphone...";

        mediaStream = await navigator.mediaDevices.getUserMedia({
            audio: true
        });

        mediaRecorder = new MediaRecorder(mediaStream);
        audioChunks = [];

        mediaRecorder.addEventListener("dataavailable", event => {

            if (event.data.size > 0) {
                audioChunks.push(event.data);
            }

        });

        mediaRecorder.addEventListener(
            "stop",
            handleRecordingStop,
            { once: true }
        );

        mediaRecorder.start();

        statusText.textContent = "Recording...";

        startButton.disabled = true;
        stopButton.disabled = false;

    } catch (error) {

        statusText.textContent = "Microphone error";

        transcriptionText.textContent =
            "Unable to access the microphone. Please check microphone permissions.";

        startButton.disabled = false;
        stopButton.disabled = true;

        stopMediaStream();
    }
});

stopButton.addEventListener("click", () => {

    if (mediaRecorder &&
        mediaRecorder.state === "recording") {

        stopButton.disabled = true;

        statusText.textContent = "Transcribing...";

        mediaRecorder.stop();
    }
});

async function handleRecordingStop() {

    try {

        const audioType =
            mediaRecorder.mimeType || "audio/webm";

        const audioBlob = new Blob(
            audioChunks,
            { type: audioType }
        );

        if (audioBlob.size === 0) {
            throw new Error("No audio was recorded.");
        }

        const formData = new FormData();

        formData.append(
            "file",
            audioBlob,
            "recording.webm"
        );

        const response = await fetch(
            "/api/transcribe",
            {
                method: "POST",
                body: formData
            }
        );

        if (!response.ok) {

            let message =
                `Transcription failed (${response.status}).`;

            try {

                const errorResponse =
                    await response.json();

                if (errorResponse.message) {
                    message = errorResponse.message;
                }

            } catch (ignored) {
                // Response was not JSON.
            }

            throw new Error(message);
        }

        const transcription =
            await response.text();

        transcriptionText.textContent =
            transcription;

        statusText.textContent = "Ready";

    } catch (error) {

        statusText.textContent = "Error";

        transcriptionText.textContent =
            error.message;

    } finally {

        stopMediaStream();

        audioChunks = [];

        startButton.disabled = false;
        stopButton.disabled = true;
    }
}

function stopMediaStream() {

    if (mediaStream) {

        mediaStream
            .getTracks()
            .forEach(track => track.stop());

        mediaStream = null;
    }
}