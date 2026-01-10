package io.github.sspanak.tt9.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.util.Logger;

/**
 * Utilities for loading Whisper models
 */
public class ModelLoader {
	private static final String LOG_TAG = "ModelLoader";
	private static final String FUTO_MODEL_BASE_URL = "https://voiceinput.futo.org/VoiceInput/";

	public interface DownloadProgressCallback {
		void onProgress(int bytesDownloaded, int totalBytes);
		void onComplete();
		void onError(Exception e);
	}

	/**
	 * Create SSL context with custom certificate
	 */
	private static SSLContext createCustomSSLContext(Context context) throws Exception {
		// Load custom certificate from raw resources
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		InputStream certInputStream = context.getResources().openRawResource(R.raw.cert);
		Certificate cert = cf.generateCertificate(certInputStream);
		certInputStream.close();

		// Create a KeyStore with the certificate
		KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		keyStore.load(null, null);
		keyStore.setCertificateEntry("custom_cert", cert);

		// Create TrustManager with the KeyStore
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(keyStore);

		// Create SSLContext
		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, tmf.getTrustManagers(), null);

		Logger.d(LOG_TAG, "Custom SSL context created with certificate");
		return sslContext;
	}

	/**
	 * Load a model from assets or filesDir
	 */
	public static MappedByteBuffer loadModel(Context context, ModelData model) throws IOException {
		if (model.isBuiltinAsset) {
			return loadFromAssets(context, model.ggmlFile);
		} else {
			return loadFromFilesDir(context, model.ggmlFile);
		}
	}

	private static MappedByteBuffer loadFromAssets(Context context, String fileName) throws IOException {
		AssetFileDescriptor fileDescriptor = context.getAssets().openFd(fileName);
		FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
		FileChannel fileChannel = inputStream.getChannel();
		long startOffset = fileDescriptor.getStartOffset();
		long declaredLength = fileDescriptor.getDeclaredLength();
		MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
		fileChannel.close();
		inputStream.close();
		fileDescriptor.close();
		return buffer;
	}

	private static MappedByteBuffer loadFromFilesDir(Context context, String fileName) throws IOException {
		File file = new File(context.getFilesDir(), fileName);
		if (!file.exists()) {
			throw new IOException("Model file not found: " + fileName);
		}
		FileInputStream inputStream = new FileInputStream(file);
		FileChannel fileChannel = inputStream.getChannel();
		MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
		buffer.load();
		fileChannel.close();
		inputStream.close();
		return buffer;
	}

	public static boolean modelExists(Context context, ModelData model) {
		if (model.isBuiltinAsset) {
			try {
				context.getAssets().openFd(model.ggmlFile).close();
				return true;
			} catch (IOException e) {
				return false;
			}
		} else {
			File file = new File(context.getFilesDir(), model.ggmlFile);
			return file.exists();
		}
	}

	/**
	 * Download a model from FUTO's server with custom certificate support
	 * This runs synchronously - call from a background thread
	 */
	public static void downloadModel(Context context, ModelData model, DownloadProgressCallback callback) {
		if (model.isBuiltinAsset) {
			Logger.d(LOG_TAG, "Model is built-in, no download needed");
			if (callback != null) callback.onComplete();
			return;
		}

		File outputFile = new File(context.getFilesDir(), model.ggmlFile);
		if (outputFile.exists()) {
			Logger.d(LOG_TAG, "Model already exists: " + model.ggmlFile);
			if (callback != null) callback.onComplete();
			return;
		}

		// Try with custom certificate first (for filtered networks)
		Logger.d(LOG_TAG, "Attempting download with custom certificate...");
		try {
			downloadWithCertificate(context, model, callback, true);
			return; // Success!
		} catch (Exception e) {
			Logger.w(LOG_TAG, "Download with custom certificate failed: " + e.getMessage());
		}

		// Fallback to default certificate (for normal networks)
		Logger.d(LOG_TAG, "Retrying download with default certificate...");
		try {
			downloadWithCertificate(context, model, callback, false);
		} catch (Exception e) {
			Logger.e(LOG_TAG, "Download failed with both certificates: " + e.getMessage());
			if (callback != null) {
				callback.onError(e);
			}
		}
	}

	private static void downloadWithCertificate(Context context, ModelData model, DownloadProgressCallback callback, boolean useCustomCert) throws Exception {
		File outputFile = new File(context.getFilesDir(), model.ggmlFile);
		File tempFile = new File(context.getCacheDir(), model.ggmlFile + ".download");
		HttpURLConnection connection = null;

		try {
			String downloadUrl = FUTO_MODEL_BASE_URL + model.ggmlFile;
			Logger.d(LOG_TAG, "Downloading from: " + downloadUrl);

			URL url = new URL(downloadUrl);
			connection = (HttpURLConnection) url.openConnection();
			
			// Use custom or default SSL certificate
			if (connection instanceof HttpsURLConnection && useCustomCert) {
				SSLContext sslContext = createCustomSSLContext(context);
				((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
				Logger.d(LOG_TAG, "Using custom SSL certificate");
			} else {
				Logger.d(LOG_TAG, "Using default SSL certificate");
			}
			
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.connect();

			if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new IOException("HTTP error: " + connection.getResponseCode());
			}

			int fileSize = connection.getContentLength();
			InputStream input = connection.getInputStream();
			FileOutputStream output = new FileOutputStream(tempFile);

			byte[] buffer = new byte[128 * 1024]; // 128KB buffer
			int bytesDownloaded = 0;
			int bytesRead;

			while ((bytesRead = input.read(buffer)) != -1) {
				output.write(buffer, 0, bytesRead);
				bytesDownloaded += bytesRead;

				if (callback != null && fileSize > 0) {
					callback.onProgress(bytesDownloaded, fileSize);
				}
			}

			output.flush();
			output.close();
			input.close();

			// Move temp file to final location
			if (!tempFile.renameTo(outputFile)) {
				throw new IOException("Failed to move downloaded file to final location");
			}

			Logger.d(LOG_TAG, "Model downloaded successfully: " + model.ggmlFile);
			if (callback != null) callback.onComplete();

		} finally {
			if (connection != null) {
				connection.disconnect();
			}
			// Clean up temp file on error
			if (!outputFile.exists() && tempFile.exists()) {
				tempFile.delete();
			}
		}
	}
}
