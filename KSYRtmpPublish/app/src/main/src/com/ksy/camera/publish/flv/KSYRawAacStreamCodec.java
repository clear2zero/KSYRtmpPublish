package com.ksy.camera.publish.flv;

class KSYRawAacStreamCodec {

	public byte protection_absent;
	// SrsAacObjectType
	public int aac_object;
	public byte sampling_frequency_index;
	public byte channel_configuration;
	public short frame_length;

	public byte sound_format;
	public byte sound_rate;
	public byte sound_size;
	public byte sound_type;
	// 0 for sh; 1 for raw data.
	public byte aac_packet_type;

	public byte[] frame;
}
