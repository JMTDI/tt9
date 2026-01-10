package io.github.sspanak.tt9.ggml;

import androidx.annotation.Keep;
import java.nio.Buffer;

/**
 * Java wrapper for GGML Whisper model
 * Provides offline speech-to-text inference using whisper.cpp
 */
public class WhisperGGML {
	static {
		System.loadLibrary("voiceinput");
	}

	private long handle = 0L;
	private final PartialResultCallback partialResultCallback;

	public interface PartialResultCallback {
		void onPartialResult(String text);
	}

	public static class BailLanguageException extends Exception {
		public final String language;

		public BailLanguageException(String language) {
			this.language = language;
		}
	}

	public WhisperGGML(Buffer modelBuffer, PartialResultCallback callback) {
		this.partialResultCallback = callback;
		this.handle = openFromBufferNative(modelBuffer);

		if (this.handle == 0L) {
			throw new IllegalArgumentException("The Whisper model could not be loaded from the given buffer");
		}
	}

	@Keep
	private void invokePartialResult(String text) {
		if (partialResultCallback != null) {
			partialResultCallback.onPartialResult(text.trim());
		}
	}

	/**
	 * Perform speech recognition inference
	 *
	 * @param samples Audio samples as float array (16kHz mono)
	 * @param prompt Optional text prompt to guide the model
	 * @param languages Array of language codes to detect/use (empty = autodetect)
	 * @param bailLanguages Languages to bail on and switch to fallback model
	 * @param decodingMode 0 = Greedy, 5 = BeamSearch5
	 * @param suppressNonSpeechTokens Whether to suppress non-speech tokens
	 * @return Transcribed text
	 * @throws BailLanguageException if a bail language was detected
	 */
	public String infer(
		float[] samples,
		String prompt,
		String[] languages,
		String[] bailLanguages,
		int decodingMode,
		boolean suppressNonSpeechTokens
	) throws BailLanguageException {
		if (handle == 0L) {
			throw new IllegalStateException("WhisperGGML has already been closed, cannot infer");
		}

		String result = inferNative(handle, samples, prompt, languages, bailLanguages, decodingMode, suppressNonSpeechTokens).trim();

		if (result.contains("<>CANCELLED<>")) {
			String[] parts = result.split("lang=");
			if (parts.length > 1) {
				throw new BailLanguageException(parts[1]);
			}
			throw new BailLanguageException("unknown");
		}

		return result;
	}

	public void close() {
		if (handle != 0L) {
			closeNative(handle);
			handle = 0L;
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			close();
		} finally {
			super.finalize();
		}
	}

	private native long openFromBufferNative(Buffer buffer);
	private native String inferNative(long handle, float[] samples, String prompt, String[] languages, String[] bailLanguages, int decodingMode, boolean suppressNonSpeechTokens);
	private native void closeNative(long handle);
}
