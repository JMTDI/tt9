package io.github.sspanak.tt9.ml;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for the voice model logic that has no Android in it: the catalog, the model chooser, the downloader.
 * Runs on a plain JDK (see .github/scripts/run_tests.sh), downloading from a small HTTP server on localhost.
 */
public final class ModelLogicTests {
	private static int checks = 0;
	private static int failures = 0;
	private static final List<File> tempDirs = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		try {
			catalog();
			chooser();
			fetcher();
			downloads();
		} finally {
			for (File dir : tempDirs) {
				deleteTree(dir);
			}
		}

		System.out.println();
		System.out.println(checks + " checks, " + failures + " failed");
		System.exit(failures == 0 ? 0 : 1);
	}


	// ---------------------------------------------------------------- catalog

	private static void catalog() {
		section("catalog");

		Set<String> ids = new HashSet<>();
		Set<String> files = new HashSet<>();
		for (ModelData model : WhisperModels.ALL) {
			check(ids.add(model.id), "id is unique: " + model.id);
			check(files.add(model.ggmlFile), "file name is unique: " + model.ggmlFile);
			check(model.digest.matches("[0-9a-f]{64}"), "SHA-256 looks right: " + model.id);
			if (!model.isBuiltinAsset) {
				check(model.ggmlFile.endsWith(".bin"), "downloadable models are .bin files: " + model.id);
				check(model.sizeBytes > 40_000_000L, "download size is set: " + model.id);
			}
		}

		eq(7, WhisperModels.ALL.length, "seven models are offered");
		eq(WhisperModels.WHISTLE, WhisperModels.ALL[0], "Whistle is listed first");
		for (int i = 1; i <= 3; i++) {
			eq(ModelData.Group.ENGLISH, WhisperModels.ALL[i].group, "English models come next, slot " + i);
		}
		for (int i = 4; i <= 6; i++) {
			eq(ModelData.Group.MULTILINGUAL, WhisperModels.ALL[i].group, "multilingual models come last, slot " + i);
		}
		eq(1, countBuiltIn(), "Whistle is the only built-in model");

		// Values FUTO publishes. If one of these changes, the app must not accept the old file.
		eq("4b5480aa1b14a7efc5b578ef176510970a898049671c3cd237285b3e3f6bfbfc", WhisperModels.ENGLISH_TINY.digest, "English Tiny checksum");
		eq("07aa4d514144deacf5ffec5cacb36c93dee272fda9e64ac33a801f8cd5cbd953", WhisperModels.MULTILINGUAL_TINY.digest, "Multilingual Tiny checksum");

		eq(WhisperModels.ENGLISH_BASE, WhisperModels.find("en-base"), "find by id");
		eq(null, WhisperModels.find("nonsense"), "unknown id is not found");
		eq(WhisperModels.WHISTLE, WhisperModels.findOrDefault("nonsense"), "unknown id means Whistle");
		eq(WhisperModels.WHISTLE, WhisperModels.findOrDefault(null), "no id means Whistle");

		eq("English Tiny", WhisperModels.ENGLISH_TINY.displayName(), "display name, English");
		eq("Multilingual Base", WhisperModels.MULTILINGUAL_BASE.displayName(), "display name, multilingual");
		eq("Whistle", WhisperModels.WHISTLE.displayName(), "display name, Whistle");
		eq(44, WhisperModels.ENGLISH_TINY.sizeMb(), "size in MB, tiny");
		eq(264, WhisperModels.MULTILINGUAL_SMALL.sizeMb(), "size in MB, small");

		eq("id", WhisperModels.mapLanguageCode("in"), "deprecated Indonesian code");
		eq("en", WhisperModels.mapLanguageCode("en_US"), "region is dropped");
		eq("no", WhisperModels.mapLanguageCode("nb"), "Norwegian variants");
	}

	private static int countBuiltIn() {
		int count = 0;
		for (ModelData model : WhisperModels.ALL) {
			if (model.isBuiltinAsset) {
				count++;
			}
		}
		return count;
	}


	// ---------------------------------------------------------------- chooser

	private static final List<String> WHISTLE_LANGUAGES = Arrays.asList("en", "de", "fr", "es", "it", "nl", "pl");

	private static VoiceModelChooser.Choice choose(ModelData selected, String language, boolean whistleRuns, ModelData... downloaded) {
		Set<String> present = new HashSet<>();
		for (ModelData model : downloaded) {
			present.add(model.id);
		}
		return VoiceModelChooser.choose(selected, language, WHISTLE_LANGUAGES.contains(language), whistleRuns, model -> present.contains(model.id));
	}

	private static void expectRun(String what, VoiceModelChooser.Choice choice, ModelData run, ModelData download) {
		eq(run, choice.run, what + ": runs");
		eq(download, choice.download, what + ": downloads");
	}

	private static void chooser() {
		section("chooser");
		ModelData whistle = WhisperModels.WHISTLE;
		VoiceModelChooser.Choice c;

		// Whistle chosen
		expectRun("Whistle, English", choose(whistle, "en", true), whistle, null);
		expectRun("Whistle, German", choose(whistle, "de", true), whistle, null);
		expectRun("Whistle, Polish", choose(whistle, "pl", true), whistle, null);
		expectRun("Whistle, Finnish, Multilingual Tiny present", choose(whistle, "fi", true, WhisperModels.MULTILINGUAL_TINY), WhisperModels.MULTILINGUAL_TINY, null);
		c = choose(whistle, "fi", true);
		expectRun("Whistle, Finnish, nothing downloaded", c, null, WhisperModels.MULTILINGUAL_TINY);
		check(!c.whistleUnavailable, "Whistle, Finnish: Whistle never knew Finnish, so it is not reported as unavailable");
		c = choose(whistle, "en", false, WhisperModels.ENGLISH_TINY);
		expectRun("Whistle cannot run, English, English Tiny present", c, WhisperModels.ENGLISH_TINY, null);
		check(c.whistleUnavailable, "Whistle cannot run: that is reported");
		c = choose(whistle, "de", false);
		expectRun("Whistle cannot run, German, nothing downloaded", c, null, WhisperModels.MULTILINGUAL_TINY);
		check(c.whistleUnavailable, "Whistle cannot run, German: reported");
		expectRun("no setting at all means Whistle", choose(null, "en", true), whistle, null);

		// An English model chosen
		ModelData enBase = WhisperModels.ENGLISH_BASE;
		expectRun("English Base, English, downloaded", choose(enBase, "en", true, enBase), enBase, null);
		c = choose(enBase, "en", true);
		expectRun("English Base, English, not downloaded, Whistle runs", c, whistle, enBase);
		check(c.whistleStandsIn, "Whistle stands in while English Base downloads");
		eq(enBase, c.wanted, "English Base is still what is wanted");
		c = choose(enBase, "en", false);
		expectRun("English Base, English, not downloaded, Whistle cannot run", c, null, enBase);
		check(!c.whistleStandsIn, "nothing stands in");
		expectRun("English Base, German", choose(enBase, "de", true), whistle, null);
		expectRun("English Base, German, Whistle cannot run, Multilingual Tiny present", choose(enBase, "de", false, WhisperModels.MULTILINGUAL_TINY), WhisperModels.MULTILINGUAL_TINY, null);
		expectRun("English Base, Finnish, nothing downloaded", choose(enBase, "fi", true), null, WhisperModels.MULTILINGUAL_TINY);
		expectRun("English Small never handles Polish", choose(WhisperModels.ENGLISH_SMALL, "pl", true, WhisperModels.ENGLISH_SMALL), whistle, null);

		// A multilingual model chosen
		ModelData multiBase = WhisperModels.MULTILINGUAL_BASE;
		expectRun("Multilingual Base, English, downloaded", choose(multiBase, "en", true, multiBase), multiBase, null);
		expectRun("Multilingual Base, Finnish, downloaded", choose(multiBase, "fi", true, multiBase), multiBase, null);
		c = choose(multiBase, "de", true);
		expectRun("Multilingual Base, German, not downloaded, Whistle runs", c, whistle, multiBase);
		check(c.whistleStandsIn, "Whistle stands in while Multilingual Base downloads");
		expectRun("Multilingual Small, Finnish, not downloaded", choose(WhisperModels.MULTILINGUAL_SMALL, "fi", true), null, WhisperModels.MULTILINGUAL_SMALL);

		// Whatever is chosen, an English-only model is never asked to do another language
		for (ModelData english : new ModelData[] {WhisperModels.ENGLISH_TINY, WhisperModels.ENGLISH_BASE, WhisperModels.ENGLISH_SMALL}) {
			for (String language : new String[] {"de", "fr", "fi", "ja", "pl"}) {
				for (boolean whistleRuns : new boolean[] {true, false}) {
					c = choose(english, language, whistleRuns, WhisperModels.ALL);
					check(c.run != null && !c.run.isEnglishOnly(), english.id + " is not used for " + language + " (Whistle runs: " + whistleRuns + ")");
				}
			}
		}
	}


	// ---------------------------------------------------------------- fetcher

	private static void fetcher() throws Exception {
		section("fetcher");
		byte[] body = randomBytes(300_000, 1);
		File dir = tempDir();

		try (TestServer server = new TestServer()) {
			// a good download
			server.serve("/ok/t1.bin", body);
			AtomicLong done = new AtomicLong();
			AtomicLong total = new AtomicLong();
			ModelFetcher.PinnedTrust broken = () -> {
				throw new IllegalStateException("the pinned certificate must not even be asked for on http");
			};
			File file = ModelFetcher.fetch(model("t1", body), dir, urls(server, "ok/"), broken, (d, t) -> {
				done.set(d);
				total.set(t);
			}, new AtomicBoolean());
			check(file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), body), "a good download arrives intact");
			check(!new File(dir, "t1.bin.part").exists(), "the .part file is gone");
			eq((long) body.length, done.get(), "progress reaches the end");
			eq((long) body.length, total.get(), "progress knows the total");

			// a file with the wrong content is rejected, and leaves nothing behind
			byte[] tampered = body.clone();
			tampered[1234] ^= 0x01;
			server.serve("/ok/t2.bin", tampered);
			try {
				ModelFetcher.fetch(model("t2", body), dir, urls(server, "ok/"), null, (d, t) -> {}, new AtomicBoolean());
				check(false, "a wrong file must be rejected");
			} catch (IOException e) {
				check(String.valueOf(e.getMessage()).contains("checksum"), "the rejection says it was the checksum: " + e.getMessage());
			}
			check(!new File(dir, "t2.bin").exists() && !new File(dir, "t2.bin.part").exists(), "a rejected file leaves nothing behind");

			// a truncated file is rejected too
			byte[] shorter = Arrays.copyOf(body, 1000);
			server.serve("/ok/t3.bin", shorter);
			try {
				ModelFetcher.fetch(model("t3", body), dir, urls(server, "ok/"), null, (d, t) -> {}, new AtomicBoolean());
				check(false, "a truncated file must be rejected");
			} catch (IOException e) {
				check(true, "a truncated file is rejected");
			}

			// the next address is tried when the first one fails
			server.status("/bad/t4.bin", 404);
			server.serve("/ok/t4.bin", body);
			file = ModelFetcher.fetch(model("t4", body), dir, urls(server, "bad/", "ok/"), null, (d, t) -> {}, new AtomicBoolean());
			check(file.isFile(), "the second address is used when the first says 404");

			// ... and when the first one serves rubbish
			server.serve("/rubbish/t5.bin", tampered);
			server.serve("/ok/t5.bin", body);
			file = ModelFetcher.fetch(model("t5", body), dir, urls(server, "rubbish/", "ok/"), null, (d, t) -> {}, new AtomicBoolean());
			check(file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), body), "the second address is used when the first serves a wrong file");

			// every address failing is reported with the reason for each
			server.status("/bad/t6.bin", 404);
			server.status("/worse/t6.bin", 500);
			try {
				ModelFetcher.fetch(model("t6", body), dir, urls(server, "bad/", "worse/"), null, (d, t) -> {}, new AtomicBoolean());
				check(false, "all addresses failing must be an error");
			} catch (IOException e) {
				String message = String.valueOf(e.getMessage());
				check(message.contains("HTTP 404") && message.contains("HTTP 500"), "the error names what went wrong everywhere: " + message);
			}

			// redirects are followed
			server.redirect("/moved/t7.bin", server.base() + "ok/t7.bin");
			server.serve("/ok/t7.bin", body);
			file = ModelFetcher.fetch(model("t7", body), dir, urls(server, "moved/"), null, (d, t) -> {}, new AtomicBoolean());
			check(file.isFile(), "a redirect is followed");

			// not enough room: stops at once, without trying the other addresses
			server.claimHugeSize("/huge/t8.bin");
			server.serve("/ok/t8.bin", body);
			try {
				ModelFetcher.fetch(model("t8", body), dir, urls(server, "huge/", "ok/"), null, (d, t) -> {}, new AtomicBoolean());
				check(false, "a file that cannot fit must be refused");
			} catch (ModelFetcher.NotEnoughSpace e) {
				check(String.valueOf(e.getMessage()).contains("free storage"), "the message talks about storage: " + e.getMessage());
			}
			eq(0, server.hits("/ok/t8.bin"), "no other address is tried after running out of space");

			// cancelling in the middle leaves nothing behind
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			server.stall("/slow/t9.bin", body, started, release);
			AtomicBoolean cancelled = new AtomicBoolean();
			AtomicReference<Throwable> outcome = new AtomicReference<>();
			Thread thread = new Thread(() -> {
				try {
					ModelFetcher.fetch(model("t9", body), dir, urls(server, "slow/"), null, (d, t) -> {}, cancelled);
				} catch (Throwable t) {
					outcome.set(t);
				}
			});
			thread.start();
			check(started.await(10, TimeUnit.SECONDS), "the slow download started");
			cancelled.set(true);
			release.countDown();
			thread.join(10_000);
			check(outcome.get() instanceof ModelFetcher.Cancelled, "cancelling stops the download: " + outcome.get());
			check(!new File(dir, "t9.bin").exists() && !new File(dir, "t9.bin.part").exists(), "a cancelled download leaves nothing behind");
		}
	}


	// -------------------------------------------------------------- downloads

	private static void downloads() throws Exception {
		section("downloads");
		ExecutorService worker = Executors.newSingleThreadExecutor();

		try (TestServer server = new TestServer()) {
			byte[] a = randomBytes(200_000, 10);
			byte[] b = randomBytes(220_000, 11);
			ModelData modelA = model("a", a);
			ModelData modelB = model("b", b);
			server.serve("/m/a.bin", a);
			server.serve("/m/b.bin", b);

			// one request for one model, however often it is asked for
			File dir = tempDir();
			ModelDownloads downloads = new ModelDownloads(dir, urls(server, "m/"), null, worker, Runnable::run);
			List<String> events = Collections.synchronizedList(new ArrayList<>());
			AtomicReference<CountDownLatch> waitFor = new AtomicReference<>(new CountDownLatch(1));
			downloads.addListener((change, snapshot) -> {
				events.add(change + ":" + (snapshot.model != null ? snapshot.model.id : "-"));
				if (change == ModelDownloads.Change.DONE || change == ModelDownloads.Change.FAILED) {
					waitFor.get().countDown();
				}
			});

			check(!downloads.isDownloaded(modelA), "nothing is downloaded at first");
			check(downloads.isDownloaded(WhisperModels.WHISTLE), "the built-in model always counts as present");
			check(downloads.start(modelA), "the first request starts a download");
			check(!downloads.start(modelA), "asking again while it runs does nothing");
			eq(ModelDownloads.Status.DOWNLOADING, downloads.snapshot().status, "the status says it is downloading");
			check(waitFor.get().await(15, TimeUnit.SECONDS), "the download finishes");
			eq(1, server.hits("/m/a.bin"), "the server saw a single request");
			check(downloads.isDownloaded(modelA), "the model is on the phone afterwards");
			check(!downloads.start(modelA), "a model that is already there is not downloaded again");
			eq(ModelDownloads.Status.IDLE, downloads.snapshot().status, "the status is idle afterwards");
			check(events.contains("STARTED:a") && events.contains("DONE:a"), "listeners heard about the start and the end: " + events);
			eq(100, downloads.snapshot().percent(), "a finished download is at 100%");

			// asking for another model drops the unfinished one
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			server.stall("/m/c.bin", randomBytes(200_000, 12), started, release);
			server.serve("/m/d.bin", randomBytes(210_000, 13));
			byte[] c = randomBytes(200_000, 12);
			byte[] d = randomBytes(210_000, 13);
			ModelData modelC = model("c", c);
			ModelData modelD = model("d", d);
			server.stall("/m/c.bin", c, started, release);
			server.serve("/m/d.bin", d);

			File dir2 = tempDir();
			ModelDownloads switching = new ModelDownloads(dir2, urls(server, "m/"), null, worker, Runnable::run);
			List<String> events2 = Collections.synchronizedList(new ArrayList<>());
			CountDownLatch doneD = new CountDownLatch(1);
			switching.addListener((change, snapshot) -> {
				events2.add(change + ":" + (snapshot.model != null ? snapshot.model.id : "-"));
				if (change == ModelDownloads.Change.DONE && snapshot.model.id.equals("d")) {
					doneD.countDown();
				}
			});
			check(switching.start(modelC), "model C starts");
			check(started.await(10, TimeUnit.SECONDS), "model C is on its way");
			check(switching.start(modelD), "asking for model D starts it");
			release.countDown();
			check(doneD.await(15, TimeUnit.SECONDS), "model D finishes");
			check(switching.isDownloaded(modelD), "model D is on the phone");
			check(!switching.isDownloaded(modelC), "model C was dropped");
			check(!new File(dir2, "c.bin.part").exists(), "model C left no partial file");
			check(!events2.contains("DONE:c") && !events2.contains("FAILED:c"), "model C reported neither success nor failure: " + events2);

			// a failure is reported, and asking again retries
			byte[] e = randomBytes(150_000, 14);
			ModelData modelE = model("e", e);
			AtomicInteger tries = new AtomicInteger();
			server.custom("/m/e.bin", exchange -> {
				if (tries.getAndIncrement() == 0) {
					exchange.sendResponseHeaders(500, -1);
				} else {
					exchange.sendResponseHeaders(200, e.length);
					try (OutputStream out = exchange.getResponseBody()) {
						out.write(e);
					}
				}
				exchange.close();
			});
			File dir3 = tempDir();
			ModelDownloads retrying = new ModelDownloads(dir3, urls(server, "m/"), null, worker, Runnable::run);
			AtomicReference<CountDownLatch> phase = new AtomicReference<>(new CountDownLatch(1));
			retrying.addListener((change, snapshot) -> {
				if (change == ModelDownloads.Change.DONE || change == ModelDownloads.Change.FAILED) {
					phase.get().countDown();
				}
			});
			check(retrying.start(modelE), "model E starts");
			check(phase.get().await(15, TimeUnit.SECONDS), "model E fails");
			ModelDownloads.Snapshot failed = retrying.snapshot();
			eq(ModelDownloads.Status.FAILED, failed.status, "the failure is kept");
			check(failed.model == modelE && failed.error != null && failed.error.contains("HTTP 500"), "the failure says why: " + failed.error);
			check(!retrying.isDownloaded(modelE), "a failed model is not downloaded");
			phase.set(new CountDownLatch(1));
			check(retrying.start(modelE), "asking again retries");
			check(phase.get().await(15, TimeUnit.SECONDS), "the retry finishes");
			check(retrying.isDownloaded(modelE) && retrying.snapshot().status == ModelDownloads.Status.IDLE, "the retry works");

			// leftovers of an interrupted run are cleaned up
			File stale = new File(dir3, "old.bin.part");
			check(stale.createNewFile(), "a stale partial file can be created");
			retrying.cleanStaleParts();
			check(!stale.exists(), "stale partial files are removed");
			check(new File(dir3, "e.bin").exists(), "finished models are kept");
		} finally {
			worker.shutdownNow();
		}
	}


	// ---------------------------------------------------------------- helpers

	private static ModelData model(String id, byte[] content) throws Exception {
		return new ModelData(id, ModelData.Group.ENGLISH, "Test", 1, id + ".bin", sha256(content), content.length);
	}

	private static List<String> urls(TestServer server, String... paths) {
		List<String> urls = new ArrayList<>();
		for (String path : paths) {
			urls.add(server.base() + path);
		}
		return urls;
	}

	private static byte[] randomBytes(int length, long seed) {
		byte[] bytes = new byte[length];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	private static String sha256(byte[] content) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static File tempDir() throws IOException {
		File dir = Files.createTempDirectory("tt9-model-test-").toFile();
		tempDirs.add(dir);
		return dir;
	}

	private static void deleteTree(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteTree(child);
			}
		}
		file.delete();
	}

	private static void section(String name) {
		System.out.println("== " + name);
	}

	private static void check(boolean ok, String what) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("  FAIL: " + what);
		}
	}

	private static void eq(Object expected, Object actual, String what) {
		check(Objects.equals(expected, actual), what + " (expected " + expected + ", got " + actual + ")");
	}


	/** A small HTTP server on localhost that serves whatever a test needs. */
	private static final class TestServer implements AutoCloseable {
		private final HttpServer http;
		private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

		TestServer() throws IOException {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			http.setExecutor(Executors.newCachedThreadPool());
			http.start();
		}

		String base() {
			return "http://127.0.0.1:" + http.getAddress().getPort() + "/";
		}

		int hits(String path) {
			AtomicInteger count = hits.get(path);
			return count != null ? count.get() : 0;
		}

		void custom(String path, HttpHandler handler) {
			http.createContext(path, exchange -> {
				hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
				try {
					handler.handle(exchange);
				} catch (IOException | RuntimeException ignored) {
					// the client hung up, which is what the cancelling tests want
				} finally {
					exchange.close();
				}
			});
		}

		void serve(String path, byte[] body) {
			custom(path, exchange -> {
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
		}

		void status(String path, int code) {
			custom(path, exchange -> exchange.sendResponseHeaders(code, -1));
		}

		void redirect(String path, String to) {
			custom(path, exchange -> {
				exchange.getResponseHeaders().add("Location", to);
				exchange.sendResponseHeaders(302, -1);
			});
		}

		void claimHugeSize(String path) {
			custom(path, exchange -> exchange.sendResponseHeaders(200, 1_000_000_000_000_000L));
		}

		/** Sends the first part, tells the test, and waits for it before sending the rest. */
		void stall(String path, byte[] body, CountDownLatch started, CountDownLatch release) {
			custom(path, exchange -> {
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body, 0, 100_000);
					out.flush();
					started.countDown();
					release.await(15, TimeUnit.SECONDS);
					out.write(body, 100_000, body.length - 100_000);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
		}

		@Override
		public void close() {
			http.stop(0);
		}
	}
}
