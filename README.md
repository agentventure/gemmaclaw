# Gemma Claw

Gemma Claw is an Android application that demonstrates on-device Large Language Model (LLM) inference using Google's Gemma models through the MediaPipe LLM Inference API.

## Features

- **On-device Inference**: Run LLMs directly on your Android device for privacy and offline capabilities.
- **Model Downloader**: Choose and download different versions of Gemma models (e.g., 2B and 7B variants).
- **Thinking Mode**: Enable a specialized "thinking" mode for the model to process complex queries.
- **Compose UI**: A modern, responsive user interface built with Jetpack Compose.

## Getting Started

### Prerequisites

- Android Studio Koala or newer.
- An Android device with at least 4GB of RAM (8GB recommended for larger models).
- Internet connection for initial model download.

### Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run the app on your device.

## Usage

1. Upon launching, select a Gemma model to download.
2. Wait for the download to complete.
3. Start chatting with Gemma!
4. Toggle "Thinking Mode" for more detailed reasoning.

## Technologies Used

- **Kotlin**: Primary programming language.
- **Jetpack Compose**: For building the UI.
- **MediaPipe LLM Inference API**: For running Gemma models on-device.
- **OkHttp**: For downloading model files.
- **Kotlin Coroutines & Flow**: For asynchronous operations and streaming responses.

## License

[Add License Info Here]
