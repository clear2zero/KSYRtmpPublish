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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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

public class MainActivity extends Activity {

	public static long pre = 0;
	// audio device.
	private AudioRecord mic;
	private byte[] audioBuffer;
	private MediaCodec audioEncoder;
	private MediaCodec.BufferInfo audioCodecBufferInfo;

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
	private Looper videoCycleLooper;
	private Handler videoCycleHandler;
	private Thread vworker;
	private boolean vloop;
	private Camera camera;
	private byte[] curframe;
	private int buffersize;
	private final long delta_pts = 0;
	private MediaCodec vencoder;
	private MediaCodec.BufferInfo vebi;
	private MediaCodecInfo vmci;

	// video camera settings.
	private Camera.Size vsize;
	private int vtrack;
	private int vcolor;

	 private String flv_url = "rtmp://192.168.135.185/myLive/drm7";
	// private String flv_url = "rtmp://115.231.96.121/xiaoyi/ksyun_test";
	// private String flv_url = "rtmp://115.231.96.121/xiaoyi/ksyun_test";
	//private String flv_url = "rtmp://115.231.96.121/xiaoyi/ksyun_test";
	// private String flv_url = "http://ossrs.net:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.1.137:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.2.111:8936/live/livestream.flv";
	// private String flv_url = "http://192.168.1.144:8936/live/livestream.flv";
	// the bitrate in kbps.
	private int vbitrate_kbps = 400;
	private static int VFPS = 20;
	private static int VGOP = 5;
	private static int VWIDTH = 640;
	private static int VHEIGHT = 480;
	private static int VDURATION = 1000 / VFPS;
	// private int prepts_set = 0;
	private final int prepts = 0;
	private int curpts = 0;
	private int delta = 0;
	private int delta_total = 5000;
	private final int delta_count = VFPS * 5;
	private final int[] pts_delta = new int[delta_count];
	private long cur_frame_num = 0;

	// encoding params.
	private long presentationTimeUs;
	private KSYRtmpFlvClient muxer;

	// settings storage
	private SharedPreferences sp;

	private static final String TAG = "guoli";
	// http://developer.android.com/reference/android/media/MediaCodec.html#createByCodecName(java.lang.String)
	private static final String VCODEC = "video/avc";// H264
	private static final String ACODEC = "audio/mp4a-latm"; // a c c

	private EditText efu;

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
		efu = (EditText) findViewById(R.id.flv_url);
		efu.setText(flv_url);

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

	private void videoCycle() {

		Thread videoCycle = new Thread(new Runnable() {

			@Override
			public void run() {

				Looper.prepare();
				videoCycleLooper = Looper.myLooper();
				videoCycleHandler = new Handler(videoCycleLooper) {

					@Override
					public void handleMessage(Message msg) {

						if (msg.what != 0x08)
							return;

						byte[] frame = (byte[]) msg.obj;
						long pts = msg.arg1;
						msg.obj = null;
						if (frame == null || frame.length <= 0)
							return;
						onGetYuvFrame(frame, pts);

					}
				};
				Looper.loop();
			}
		});
		videoCycle.start();
	}

	@SuppressWarnings("deprecation")
	private void publish(Object onYuvFrame, SurfaceHolder holder) {

		flv_url = efu.getText().toString();
		if (vbitrate_kbps <= 10) {
			Log.e(TAG, String.format("video bitrate must 10kbps+, actual is %d", vbitrate_kbps));
			return;
		}
		if (!flv_url.startsWith("http://") && !flv_url.startsWith("rtmp://")) {
			Log.e(TAG, String.format("flv url must starts with http://, actual is %s", flv_url));
			return;
		}

				muxer = new KSYRtmpFlvClient(flv_url);
		try {
			muxer.start();
		} catch (IOException e) {
			Log.e(TAG, "start muxer failed.");
			e.printStackTrace();
			return;
		}

		// the pts for video and audio encoder.
		presentationTimeUs = new Date().getTime();
		// open mic, to find the work one.
		if ((mic = chooseAudioDevice()) == null) {
			Log.e(TAG, String.format("mic find device mode failed."));
			return;
		}

		// aencoder yuv to aac raw stream.
		// requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
		try {
			audioEncoder = MediaCodec.createEncoderByType(ACODEC); // auido
			// codec

		} catch (IOException e) {
			Log.e(TAG, "create aencoder failed.");
			e.printStackTrace();
			return;
		}
		audioCodecBufferInfo = new MediaCodec.BufferInfo();// audio codec buffer

		// setup the aencoder.
		// @see
		// https://developer.android.com/reference/android/media/MediaCodec.html
		MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, asample_rate, achannel);
		aformat.setInteger(MediaFormat.KEY_BIT_RATE, 1000 * ABITRATE_KBPS); // 音频码率
		aformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
		audioEncoder.configure(aformat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

		// add the video tracker to muxer.
		atrack = muxer.addTrack(aformat); // 添加音轨
		Log.i(TAG, String.format("muxer add audio track index=%d", atrack));

		// open camera.
		camera = Camera.open(1);// N ， 0- (n-1)
		// int number = Camera.getNumberOfCameras();
		// Toast.makeText(this, "camera number :" + number,
		// Toast.LENGTH_LONG).show();
		Camera.Parameters parameters = camera.getParameters();
		// parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
		// parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
		// parameters.setSceneMode(Camera.Parameters.SCENE_MODE_LANDSCAPE);
		// parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
		// parameters.setColorEffect(Camera.Parameters.EFFECT_NEGATIVE);
		// 每一款手机支持都不一致

		parameters.setPreviewFormat(ImageFormat.YV12); // 图像格式

		// List<Integer> previewFormaters =
		// parameters.getSupportedPreviewFormats();

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
		parameters.setPreviewFrameRate(VFPS);// 视频帧率
		// parameters.setPreviewFpsRange((VFPS - 1) * 1000, (VFPS + 1) * 1000);
		// parameters.getSupportedPreviewFpsRange();
		Log.i(TAG, String.format("set the preview size in %dx%d", size.width, size.height));

		camera.setDisplayOrientation(90);
		try {
			camera.setParameters(parameters);
		} catch (Exception e) {
			e.printStackTrace();
		}

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
		vformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP);// I帧间隔,间隔大，视频大小，间隔小，码率大
		vformat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);// 固定码率，可变码率，固定量化参数
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

		VDURATION = 1000 / VFPS;

		// set the callback and start the preview.
		buffersize = getYuvBuffer(size.width, size.height);
		for (int j = 0; j < delta_count; j++)
			pts_delta[j] = VDURATION;
		for (int i = 0; i < VFPS; i++)
		{
			byte[] vbuffer = new byte[buffersize];
			camera.addCallbackBuffer(vbuffer);
		}
		curframe = new byte[buffersize];
		camera.setPreviewCallbackWithBuffer((Camera.PreviewCallback) onYuvFrame);
		try {
			camera.setPreviewDisplay(holder);
		} catch (IOException e) {
			Log.e(TAG, "preview video failed.");
			e.printStackTrace();
			return;
		}
		videoCycle();

		// start device and encoder.
		Log.i(TAG, "start avc vencoder");
		vencoder.start();
		vloop = true;
		vworker = new Thread(new Runnable() {

			// @Override
			@Override
			public void run() {

				fetchVideoFromEncode();
			}
		});
		vworker.start();

		Log.i(TAG, "start aac aencoder");
		audioEncoder.start();
		Log.i(TAG, String.format("start to preview video in %dx%d, vbuffer %dB", size.width, size.height, buffersize));
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

	private int get_delta(int index, int delta) {

		delta_total = delta_total - pts_delta[index] + delta;
		pts_delta[index] = delta;
		return (delta_total + delta_count / 2) / delta_count;
	}

	// when got YUV frame from camera.
	// @see
	// https://developer.android.com/reference/android/media/MediaCodec.html
	private Object fetchVideoFromDevice() {

		return new Camera.PreviewCallback() {

			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {

				// to fetch next frame.
				curpts = (int) ((new Date().getTime() - presentationTimeUs));// /
																				// VDURATION
																				// *
																				// VDURATION;

				Message msg = videoCycleHandler.obtainMessage();
				msg.what = 0x08;
				msg.obj = data;
				// msg.arg1 = (int) ((new Date().getTime() - presentationTimeUs
				// + VDURATION / 2) / VDURATION * VDURATION);
				msg.arg1 = curpts;
				// msg.arg1 = curpts;
				// Log.i("guoli",String.format("onPreviewFrame frame_num:%d realtime:%d settime:%d delta=%d %d %d",
				// cur_frame_num, curpts, msg.arg1, delta, msg.arg1 - curpts,
				// curpts - prepts ));

				// prepts_set = msg.arg1;
				videoCycleHandler.sendMessage(msg);
			}
		};
	}

	private void fetchVideoFromEncode() {

		if (vencoder == null)
			return;
		while (vloop && vencoder != null && !Thread.interrupted()) {
			ByteBuffer[] outBuffers = vencoder.getOutputBuffers();
			int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, VDURATION / 4);
			// Log.i(TAG,
			// String.format("try to dequeue output vbuffer, ii=%d, oi=%d",
			// inBufferIndex, outBufferIndex));
			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex];
				onEncodedAnnexbFrame(bb, vebi);
				vencoder.releaseOutputBuffer(outBufferIndex, false);
			}
		}
	}

	private void fetchAudioFromDevice() {

		while (aloop && mic != null && !Thread.interrupted()) {
			int size = mic.read(audioBuffer, 0, audioBuffer.length); // PCM 原轨数据
			if (size <= 0) {
				Log.i(TAG, "audio ignore, no data to read.");
				break;
			}

			// byte[] audio = new byte[size];
			// System.arraycopy(abuffer, 0, audio, 0, size);

			onGetPcmFrame(audioBuffer);
		}
	}

	private void dispose() {

		vloop = false;
		if (vworker != null) {
			Log.i(TAG, "stop video worker thread");
			vworker.interrupt();
			try {
				vworker.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			vworker = null;
		}

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

		if (audioEncoder != null) {
			Log.i(TAG, "stop aencoder");
			audioEncoder.stop();
			audioEncoder.release();
			audioEncoder = null;
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

	private void onGetYuvFrame(byte[] data, long pts) {

		// Log.i(TAG, String.format("got YUV image, size=%d", data.length));
//		if (cur_frame_num > 0) {
//			delta = curpts - prepts;
//			// if(delta < VDURATION / 3) {
//			// Log.i("guoli",String.format("onPreviewFrame DROP FRAME frame_num:%d realtime:%d pretime:%d delta=%d",
//			// cur_frame_num, curpts, prepts, curpts - prepts));
//			// camera.addCallbackBuffer(data);
//			// return;
//			// }
//			delta = get_delta((int) (cur_frame_num % delta_count), delta);
//		} else {
//			// prepts_set = curpts;
//			delta = 0;
//		}

		cur_frame_num++;

		// long a = new Date().getTime();
		// 裸流数据转换
		if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
			KSYUtils.YV12toYUV420Planar(data, curframe, vsize.width,
					vsize.height);
		} else if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) {
			KSYUtils.YV12toYUV420PackedSemiPlanar(data, curframe,
					vsize.width, vsize.height);
		} else if (vcolor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
			KSYUtils.YV12toYUV420PackedSemiPlanar(data, curframe,
					vsize.width, vsize.height);
		} else {
			System.arraycopy(data, 0, curframe, 0, data.length);
		}
		camera.addCallbackBuffer(data);
		// a = new Date().getTime() - a;

		// Log.i("guoli",String.format("onGetYuvFrame yuv convert delta=%d",a));
		// feed the vencoder with yuv frame, got the encoded 264 es stream.
		ByteBuffer[] inBuffers = vencoder.getInputBuffers();

		if (true) {
			int inBufferIndex = vencoder.dequeueInputBuffer(-1);
			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(curframe, 0, curframe.length);
				// long pts = (new Date().getTime() - presentationTimeUs) /
				// VDURATION * VDURATION;
				vencoder.queueInputBuffer(inBufferIndex, 0, curframe.length, pts, 0);
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
		ByteBuffer[] inBuffers = audioEncoder.getInputBuffers();
		ByteBuffer[] outBuffers = audioEncoder.getOutputBuffers();

		if (true) {
			int inBufferIndex = audioEncoder.dequeueInputBuffer(-1);
			// Log.i(TAG, String.format("try to dequeue input vbuffer, ii=%d",
			// inBufferIndex));
			if (inBufferIndex >= 0) {
				ByteBuffer bb = inBuffers[inBufferIndex];
				bb.clear();
				bb.put(data, 0, data.length);
				long pts = new Date().getTime() - presentationTimeUs;
				// Log.i(TAG, String.format("feed PCM to encode %dB, pts=%d",
				// data.length, pts / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, data, data.length);
				audioEncoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
			}
		}

		for (;;) {
			int outBufferIndex = audioEncoder.dequeueOutputBuffer(audioCodecBufferInfo, 0);
			// Log.i(TAG,
			// String.format("try to dequeue output vbuffer, ii=%d, oi=%d",
			// inBufferIndex, outBufferIndex));
			if (outBufferIndex >= 0) {
				ByteBuffer bb = outBuffers[outBufferIndex]; //
				// Log.i(TAG, String.format("encoded aac %dB, pts=%d",
				// aebi.size, aebi.presentationTimeUs / 1000));
				// SrsHttpFlv.srs_print_bytes(TAG, bb, aebi.size);
				onEncodedAacFrame(bb, audioCodecBufferInfo); // AAC
				audioEncoder.releaseOutputBuffer(outBufferIndex, false);
			} else {
				break;
			}
		}
	}

	// @remark thanks for baozi.
	public AudioRecord chooseAudioDevice() {

		int[] sampleRates = { 44100, 22050, 11025 }; // 采样率 , 单位千赫兹
		for (int sampleRate : sampleRates) {
			int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
			int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_STEREO;

			int bSamples = 8;
			if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
				bSamples = 16;// 量化大小
			}

			int nChannels = 2;// 声道
			if (channelConfig == AudioFormat.CHANNEL_CONFIGURATION_MONO) {
				nChannels = 1;
			}

			// int bufferSize = 2 * bSamples * nChannels / 8;
			int bufferSize = 5 * AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
			AudioRecord audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);

			if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
				Log.e(TAG, "initialize the mic failed.");
				continue;
			}

			asample_rate = sampleRate;
			abits = audioFormat;
			achannel = nChannels;
			mic = audioRecorder;
			audioBuffer = new byte[Math.min(4096, bufferSize)];
			// abuffer = new byte[bufferSize];
			Log.i(TAG, String.format("mic open rate=%dHZ, channels=%d, bits=%d, buffer=%d/%d, state=%d",
					sampleRate, nChannels, bSamples, bufferSize, audioBuffer.length, audioRecorder.getState()));
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
		// 2. perfer qcom avc.
		// 3. perfer google avc.

		List<MediaCodecInfo> vmcis = new ArrayList<MediaCodecInfo>();
		vmci = chooseVideoEncoder(null, null);// 获取编码器
		vmci = chooseVideoEncoder("google", vmci);
		vmci = chooseVideoEncoder("qcom", vmci); // 确定使用qcom编码器

		int matchedColorFormat = 0;
		MediaCodecInfo.CodecCapabilities cc = vmci.getCapabilitiesForType(VCODEC);
		for (int i = 0; i < cc.colorFormats.length; i++) {
			int cf = cc.colorFormats[i];
			Log.i("color", String.format("vencoder %s supports color fomart 0x%x(%d)", vmci.getName(), cf, cf));

			// 确定色彩空间
			// choose YUV for h.264, prefer the bigger one.
			if ((cf >= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411Planar && cf <= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar)) {
				if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
					matchedColorFormat = cf;
					break;
				}
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
