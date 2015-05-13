package com.ksy.camera.publish;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {

	// audio device.
	private AudioRecord mic;
	private byte[] abuffer;
	private MediaCodec aencoder;
	private MediaCodec.BufferInfo aebi;

	// use worker thread to get audio packet.
	private Thread aworker;
	private boolean aloop;

	// audio mic settings.
	private int asample_rate;
	private int achannel;
	private int abits;
	private int atrack;
	private static final int ABITRATE_KBPS = 24;

	// video device.
	private Camera camera;
	private byte[] vbuffer;
	private MediaCodec vencoder;
	private MediaCodec.BufferInfo vebi;
	private MediaCodecInfo vmci;

	// video camera settings.
	private Camera.Size vsize;
	private int vtrack;
	private int vcolor;

	private String flv_url = "rtmp://192.168.135.185/myLive/drm3";
	// private String flv_url = "http://ossrs.net:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.1.137:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.2.111:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.1.144:8936/live/livestream.flv";
	// the bitrate in kbps.
	private int vbitrate_kbps = 300;
	private final static int VFPS = 20;
	private final static int VGOP = 20;
	private final static int VWIDTH = 1280;
	private final static int VHEIGHT = 720;

	// encoding params.
	private long presentationTimeUs;
	private KSYRtmpFlvClient muxer;

	// settings storage
	private SharedPreferences sp;

	private static final String TAG = "guoli";
	// http://developer.android.com/reference/android/media/MediaCodec.html#createByCodecName(java.lang.String)
	private static final String VCODEC = "video/avc";
	private static final String ACODEC = "audio/mp4a-latm";

	public MainActivity() {

		camera = null;
		vencoder = null;
		muxer = null;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		sp = getSharedPreferences("SrsPublisher", MODE_PRIVATE);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_main);

		// restore data.
		vbitrate_kbps = sp.getInt("VBITRATE", vbitrate_kbps);
		Log.i(TAG, String.format("initialize flv url to %s, vbitrate=%dkbps", flv_url, vbitrate_kbps));

		// initialize url.
		final EditText efu = (EditText) findViewById(R.id.flv_url);
		efu.setText(flv_url);
		efu.addTextChangedListener(new TextWatcher() {

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {

			}

			@Override
			public void afterTextChanged(Editable s) {

				String fu = efu.getText().toString();
				if (fu == flv_url || fu.isEmpty()) {
					return;
				}

				flv_url = fu;
				Log.i(TAG, String.format("flv url changed to %s", flv_url));

				SharedPreferences.Editor editor = sp.edit();
				editor.putString("FLV_URL", flv_url);
				editor.commit();
			}
		});

		final EditText evb = (EditText) findViewById(R.id.vbitrate);
		evb.setText(String.format("%dkbps", vbitrate_kbps));
		evb.addTextChangedListener(new TextWatcher() {

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {

			}

			@Override
			public void afterTextChanged(Editable s) {

				int vb = Integer.parseInt(evb.getText().toString().replaceAll("kbps", ""));
				if (vb == vbitrate_kbps) {
					return;
				}

				vbitrate_kbps = vb;
				Log.i(TAG, String.format("video bitrate changed to %d", vbitrate_kbps));

				SharedPreferences.Editor editor = sp.edit();
				editor.putInt("VBITRATE", vbitrate_kbps);
				editor.commit();
			}
		});

		// for camera, @see
		// https://developer.android.com/reference/android/hardware/Camera.html
		final Button btnPublish = (Button) findViewById(R.id.capture);
		final Button btnStop = (Button) findViewById(R.id.stop);
		final SurfaceView preview = (SurfaceView) findViewById(R.id.camera_preview);
		btnPublish.setEnabled(true);
		btnStop.setEnabled(false);

		btnStop.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {

				dispose();

				btnPublish.setEnabled(true);
				btnStop.setEnabled(false);
			}
		});

		btnPublish.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {

				dispose();
				publish(fetchVideoFromDevice(), preview.getHolder());
				btnPublish.setEnabled(false);
				btnStop.setEnabled(true);
			}
		});

		Intent service = new Intent(this, LogService.class);
		startService(service);
	}

	private void publish(Object onYuvFrame, SurfaceHolder holder) {

		if (vbitrate_kbps <= 10) {
			Log.e(TAG, String.format("video bitrate must 10kbps+, actual is %d", vbitrate_kbps));
			return;
		}
		if (!flv_url.startsWith("http://") && !flv_url.startsWith("rtmp://")) {
			Log.e(TAG, String.format("flv url must starts with http://, actual is %s", flv_url));
			return;
		}
		// if (!flv_url.endsWith(".flv")) {
		// Log.e(TAG, String.format("flv url must ends with .flv, actual is %s",
		// flv_url));
		// return;
		// }

		// start the muxer to POST stream to SRS over HTTP FLV.
		muxer = new KSYRtmpFlvClient(flv_url, KSYHttpFlvClient.OutputFormat.MUXER_OUTPUT_HTTP_FLV);
		try {
			muxer.start();
		} catch (IOException e) {
			Log.e(TAG, "start muxer failed.");
			e.printStackTrace();
			return;
		}
		Log.i(TAG, String.format("start muxer to SRS over HTTP FLV, url=%s", flv_url));

		// the pts for video and audio encoder.
		// presentationTimeUs = new Date().getTime();

		// open mic, to find the work one.
		if ((mic = chooseAudioDevice()) == null) {
			Log.e(TAG, String.format("mic find device mode failed."));
			return;
		}

		// aencoder yuv to aac raw stream.
		// requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
		try {
			aencoder = MediaCodec.createEncoderByType(ACODEC); // auido codec
		} catch (IOException e) {
			Log.e(TAG, "create aencoder failed.");
			e.printStackTrace();
			return;
		}
		aebi = new MediaCodec.BufferInfo();// audio codec buffer

		// setup the aencoder.
		// @see
		// https://developer.android.com/reference/android/media/MediaCodec.html
		MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, asample_rate, achannel);
		aformat.setInteger(MediaFormat.KEY_BIT_RATE, 1000 * ABITRATE_KBPS); // 音频码率
		aformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
		aencoder.configure(aformat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

		// add the video tracker to muxer.
		atrack = muxer.addTrack(aformat); // 添加音轨
		Log.i(TAG, String.format("muxer add audio track index=%d", atrack));

		// open camera.
		camera = Camera.open(0);
		int number = Camera.getNumberOfCameras();
		Toast.makeText(this, "camera number :" + number, Toast.LENGTH_LONG).show();
		Camera.Parameters parameters = camera.getParameters();

		parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
		parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
		parameters.setSceneMode(Camera.Parameters.SCENE_MODE_LANDSCAPE);
		parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
		// parameters.setColorEffect(Camera.Parameters.EFFECT_NEGATIVE);
		parameters.setPreviewFormat(ImageFormat.YV12); // 图像格式

		// parameters.set("orientation", "portrait");
		// parameters.set("orientation", "landscape");
		// parameters.setRotation(0);

		Camera.Size size = null;
		List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
		for (int i = 0; i < sizes.size(); i++) {
			Camera.Size s = sizes.get(i);
			// Log.i(TAG, String.format("camera supported picture size %dx%d",
			// s.width, s.height));
			if (size == null) {
				if (s.height == VHEIGHT) {
					size = s;
				}
			} else {
				if (s.width == VWIDTH) {
					size = s;
				}
			}
		}
		parameters.setPictureSize(size.width, size.height);
		Log.i(TAG, String.format("set the picture size in %dx%d", size.width, size.height));

		size = null;
		sizes = parameters.getSupportedPreviewSizes();
		for (int i = 0; i < sizes.size(); i++) {
			Camera.Size s = sizes.get(i);
			// Log.i(TAG, String.format("camera supported preview size %dx%d",
			// s.width, s.height));
			if (size == null) {
				if (s.height == VHEIGHT) {
					size = s;
				}
			} else {
				if (s.width == VWIDTH) {
					size = s;
				}
			}
		}
		vsize = size;
		parameters.setPreviewSize(size.width, size.height);
		Log.i(TAG, String.format("set the preview size in %dx%d", size.width, size.height));

		camera.setDisplayOrientation(90);
		camera.setParameters(parameters);

		// choose the right vencoder, perfer qcom then google.
		vcolor = chooseVideoEncoder(); // 确定图像输入格式,优先高通
		// vencoder yuv to 264 es stream.
		// requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
		try {
			vencoder = MediaCodec.createByCodecName(vmci.getName()); // 创建图像编码器
		} catch (IOException e) {
			Log.e(TAG, "create vencoder failed.");
			e.printStackTrace();
			return;
		}
		vebi = new MediaCodec.BufferInfo();// video buffer

		// setup the vencoder.
		// @see
		// https://developer.android.com/reference/android/media/MediaCodec.html
		MediaFormat vformat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vsize.width, vsize.height);
		vformat.setInteger(MediaFormat.KEY_COLOR_FORMAT, vcolor);
		vformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
		vformat.setInteger(MediaFormat.KEY_BIT_RATE, 1000 * vbitrate_kbps);// 码率
		vformat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);// 帧率
		vformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP);// I帧间隔
		Log.i(TAG, String.format("vencoder %s, color=%d, bitrate=%d, fps=%d, gop=%d, size=%dx%d",
				vmci.getName(), vcolor, vbitrate_kbps, VFPS, VGOP, vsize.width, vsize.height));
		// the following error can be ignored:
		// 1. the storeMetaDataInBuffers error:
		// [OMX.qcom.video.encoder.avc] storeMetaDataInBuffers (output) failed
		// w/ err -2147483648
		// @see http://bigflake.com/mediacodec/#q12
		vencoder.configure(vformat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

		// add the video tracker to muxer.
		vtrack = muxer.addTrack(vformat);// 添加视频
		Log.i(TAG, String.format("muxer add video track index=%d", vtrack));

		// set the callback and start the preview.
		vbuffer = new byte[getYuvBuffer(size.width, size.height)];
		camera.addCallbackBuffer(vbuffer);
		camera.setPreviewCallbackWithBuffer((Camera.PreviewCallback) onYuvFrame);
		try {
			camera.setPreviewDisplay(holder);
		} catch (IOException e) {
			Log.e(TAG, "preview video failed.");
			e.printStackTrace();
			return;
		}

		// start device and encoder.
		Log.i(TAG, "start avc vencoder");
		vencoder.start();
		Log.i(TAG, "start aac aencoder");
		aencoder.start();
		Log.i(TAG, String.format("start to preview video in %dx%d, vbuffer %dB", size.width, size.height, vbuffer.length));
		camera.startPreview();
		Log.i(TAG, String.format("start the mic in rate=%dHZ, channels=%d, format=%d", asample_rate, achannel, abits));
		mic.startRecording();
		// start audio worker thread.
		aworker = new Thread(new Runnable() {

			// @Override
			@Override
			public void run() {

				fetchAudioFromDevice();
			}
		});
		// Log.i(TAG, "start audio worker thread.");
		aloop = true;
		aworker.start();
	}

	// when got YUV frame from camera.
	// @see
	// https://developer.android.com/reference/android/media/MediaCodec.html
	private Object fetchVideoFromDevice() {

		return new Camera.PreviewCallback() {

			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {

				// color space transform.
				byte[] frame = new byte[data.length];
				if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
					KSYUtils.YV12toYUV420Planar(data, frame, vsize.width, vsize.height);
				} else if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) {
					KSYUtils.YV12toYUV420PackedSemiPlanar(data, frame, vsize.width, vsize.height);
				} else if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
					KSYUtils.YV12toYUV420PackedSemiPlanar(data, frame, vsize.width, vsize.height);
				} else {
					System.arraycopy(data, 0, frame, 0, data.length);
				}

				// feed the frame to vencoder and muxer.
				try {
					onGetYuvFrame(frame);
				} catch (Exception e) {
					Log.e(TAG, String.format("consume yuv frame failed. e=%s", e.toString()));
					e.printStackTrace();
					try {
						throw e;
					} catch (Exception e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				}

				// to fetch next frame.
				camera.addCallbackBuffer(vbuffer);
			}
		};
	}

	private void fetchAudioFromDevice() {

		while (aloop && mic != null && !Thread.interrupted()) {
			int size = mic.read(abuffer, 0, abuffer.length);
			if (size <= 0) {
				Log.i(TAG, "audio ignore, no data to read.");
				break;
			}

			byte[] audio = new byte[size];
			System.arraycopy(abuffer, 0, audio, 0, size);

			onGetPcmFrame(audio);
		}
	}

	private void dispose() {

		aloop = false;
		if (aworker != null) {
			Log.i(TAG, "stop audio worker thread");
			aworker.interrupt();
			try {
				aworker.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			aworker = null;
		}

		if (mic != null) {
			Log.i(TAG, "stop mic");
			mic.setRecordPositionUpdateListener(null);
			mic.stop();
			mic.release();
			mic = null;
		}

		if (camera != null) {
			Log.i(TAG, "stop preview");
			camera.setPreviewCallbackWithBuffer(null);
			camera.stopPreview();
			camera.release();
			camera = null;
		}

		if (aencoder != null) {
			Log.i(TAG, "stop aencoder");
			aencoder.stop();
			aencoder.release();
			aencoder = null;
		}

		if (vencoder != null) {
			Log.i(TAG, "stop vencoder");
			vencoder.stop();
			vencoder.release();
			vencoder = null;
		}

		if (muxer != null) {
			Log.i(TAG, "stop muxer to SRS over HTTP FLV");
			muxer.stop();
			muxer.release();
			muxer = null;
		}
	}

	@Override
	protected void onResume() {

		super.onResume();

		final Button btn = (Button) findViewById(R.id.capture);
		btn.setEnabled(true);
	}

	@Override
	protected void onPause() {

		super.onPause();
		dispose();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		// noinspection SimplifiableIfStatement
		if (id == R.id.action_settings) {
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	// when got encoded h264 es stream.
	private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {

		try {
			muxer.writeSampleData(vtrack, es, bi);
		} catch (Exception e) {
			Log.e(TAG, "muxer write video sample failed.");
			e.printStackTrace();
		}
	}

	private void onGetYuvFrame(byte[] data) {

		// Log.i(TAG, String.format("got YUV image, size=%d", data.length));

		// feed the vencoder with yuv frame, got the encoded 264 es stream.
		ByteBuffer[] inBuffers = vencoder.getInputBuffers();
		ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

		if (true) {
			int inBufferIndex = vencoder.dequeueInputBuffer(-1);
			// Log.i(TAG, String.format("try to dequeue input vbuffer, ii=%d",
			// inBufferIndex));
			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(data, 0, data.length);
				if (presentationTimeUs == 0)
					presentationTimeUs = new Date().getTime();
				long pts = new Date().getTime() - presentationTimeUs;
				// Log.i(TAG, String.format("feed YUV to encode %dB, pts=%d",
				// data.length, pts / 1000));
				vencoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
			}
		}

		for (;;) {
			int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
			// Log.i(TAG,
			// String.format("try to dequeue output vbuffer, ii=%d, oi=%d",
			// inBufferIndex, outBufferIndex));
			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex];
				onEncodedAnnexbFrame(bb, vebi);
				vencoder.releaseOutputBuffer(outBufferIndex, false);
			}

			if (outBufferIndex < 0) {
				break;
			}
		}
	}

	// when got encoded aac raw stream.
	private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {

		try {
			muxer.writeSampleData(atrack, es, bi);
		} catch (Exception e) {
			Log.e(TAG, "muxer write audio sample failed.");
			e.printStackTrace();
		}
	}

	private void onGetPcmFrame(byte[] data) {

		// Log.i(TAG, String.format("got PCM audio, size=%d", data.length));

		// feed the aencoder with yuv frame, got the encoded 264 es stream.
		ByteBuffer[] inBuffers = aencoder.getInputBuffers();
		ByteBuffer[] outBuffers = aencoder.getOutputBuffers();

		if (true) {
			int inBufferIndex = aencoder.dequeueInputBuffer(-1);
			// Log.i(TAG, String.format("try to dequeue input vbuffer, ii=%d",
			// inBufferIndex));
			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(data, 0, data.length);
				if (presentationTimeUs == 0)
					presentationTimeUs = new Date().getTime();
				long pts = new Date().getTime() - presentationTimeUs;
				// Log.i(TAG, String.format("feed PCM to encode %dB, pts=%d",
				// data.length, pts / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, data, data.length);
				aencoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
			}
		}

		for (;;) {
			int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
			// Log.i(TAG,
			// String.format("try to dequeue output vbuffer, ii=%d, oi=%d",
			// inBufferIndex, outBufferIndex));
			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex];
				// Log.i(TAG, String.format("encoded aac %dB, pts=%d",
				// aebi.size, aebi.presentationTimeUs / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, bb, aebi.size);
				onEncodedAacFrame(bb, aebi);
				aencoder.releaseOutputBuffer(outBufferIndex, false);
			} else {
				break;
			}
		}
	}

	// @remark thanks for baozi.
	public AudioRecord chooseAudioDevice() {

		int[] sampleRates = { 44100, 22050, 11025 };
		for (int sampleRate : sampleRates) {
			int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
			int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_STEREO;

			int bSamples = 8;
			if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
				bSamples = 16;
			}

			int nChannels = 2;
			if (channelConfig == AudioFormat.CHANNEL_CONFIGURATION_MONO) {
				nChannels = 1;
			}

			// int bufferSize = 2 * bSamples * nChannels / 8;
			int bufferSize = 2 * AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
			AudioRecord audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);

			if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
				Log.e(TAG, "initialize the mic failed.");
				continue;
			}

			asample_rate = sampleRate;
			abits = audioFormat;
			achannel = nChannels;
			mic = audioRecorder;
			abuffer = new byte[Math.min(4096, bufferSize)];
			// abuffer = new byte[bufferSize];
			Log.i(TAG, String.format("mic open rate=%dHZ, channels=%d, bits=%d, buffer=%d/%d, state=%d",
					sampleRate, nChannels, bSamples, bufferSize, abuffer.length, audioRecorder.getState()));
			break;
		}

		return mic;
	}

	// for the vbuffer for YV12(android YUV), @see below:
	// https://developer.android.com/reference/android/hardware/Camera.Parameters.html#setPreviewFormat(int)
	// https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12
	private int getYuvBuffer(int width, int height) {

		// stride = ALIGN(width, 16)
		int stride = (int) Math.ceil(width / 16.0) * 16;
		// y_size = stride * height
		int y_size = stride * height;
		// c_stride = ALIGN(stride/2, 16)
		int c_stride = (int) Math.ceil(width / 32.0) * 16;
		// c_size = c_stride * height/2
		int c_size = c_stride * height / 2;
		// size = y_size + c_size * 2
		return y_size + c_size * 2;
	}

	// choose the video encoder by name.
	private MediaCodecInfo chooseVideoEncoder(String name, MediaCodecInfo def) {

		int nbCodecs = MediaCodecList.getCodecCount();
		for (int i = 0; i < nbCodecs; i++) {
			MediaCodecInfo mci = MediaCodecList.getCodecInfoAt(i);
			if (!mci.isEncoder()) {
				continue;
			}

			String[] types = mci.getSupportedTypes();
			for (int j = 0; j < types.length; j++) {
				if (types[j].equalsIgnoreCase(VCODEC)) {
					// Log.i(TAG, String.format("vencoder %s types: %s",
					// mci.getName(), types[j]));
					if (name == null) {
						return mci;
					}

					if (mci.getName().contains(name)) {
						return mci;
					}
				}
			}
		}

		return def;
	}

	// choose the right supported color format. @see below:
	// https://developer.android.com/reference/android/media/MediaCodecInfo.html
	// https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities.html
	private int chooseVideoEncoder() {

		// choose the encoder "video/avc":
		// 1. select one when type matched.
		// 2. perfer google avc.
		// 3. perfer qcom avc.
		List<MediaCodecInfo> vmcis = new ArrayList<MediaCodecInfo>();
		vmci = chooseVideoEncoder(null, null);// 获取编码器
		vmci = chooseVideoEncoder("google", vmci);
		vmci = chooseVideoEncoder("qcom", vmci);

		int matchedColorFormat = 0;
		MediaCodecInfo.CodecCapabilities cc = vmci.getCapabilitiesForType(VCODEC);
		for (int i = 0; i < cc.colorFormats.length; i++) {
			int cf = cc.colorFormats[i];
			Log.i("color", String.format("vencoder %s supports color fomart 0x%x(%d)", vmci.getName(), cf, cf));

			// choose YUV for h.264, prefer the bigger one.
			if ((cf >= cc.COLOR_FormatYUV411Planar && cf <= cc.COLOR_FormatYUV422SemiPlanar)) {
				if (cf > matchedColorFormat) {
					matchedColorFormat = cf;
				}
			}
		}

		for (int i = 0; i < cc.profileLevels.length; i++) {
			MediaCodecInfo.CodecProfileLevel pl = cc.profileLevels[i];
			Log.i(TAG, String.format("vencoder %s support profile %d, level %d", vmci.getName(), pl.profile, pl.level));
		}

		Log.i(TAG, String.format("vencoder %s choose color format 0x%x(%d)", vmci.getName(), matchedColorFormat, matchedColorFormat));
		return matchedColorFormat;
	}

}
