package net.openosrs.api.dispatch;

import java.io.IOException;
import java.nio.charset.Charset;

/** Legacy internal encoder retained for compatibility; active dispatch uses the native buffer. */
@Deprecated
final class BufferWriter
{
	private static final Charset CP_1252 = Charset.forName("windows-1252");

	private final byte[] payload;
	private int offset;

	BufferWriter(byte[] payload, int offset)
	{
		this.payload = payload;
		this.offset = offset;
	}

	int offset()
	{
		return offset;
	}

	void write(String method, Object value, Integer operand) throws IOException
	{
		if ("writeStringCp1252NullTerminated".equals(method))
		{
			writeString(String.valueOf(value));
			return;
		}

		int number = value instanceof Boolean
			? ((Boolean) value ? 1 : 0)
			: value instanceof Number ? ((Number) value).intValue() : 0;
		if (operand != null)
		{
			number += operand;
		}

		switch (method)
		{
			case "writeByte":
				writeByte(number);
				break;
			case "writeByteAdd":
				writeByte(number + 128);
				break;
			case "writeByteNeg":
				writeByte(-number);
				break;
			case "writeByteSub":
				writeByte(128 - number);
				break;
			case "writeShort":
				writeShort(number);
				break;
			case "writeShortLE":
				writeShortLE(number);
				break;
			case "writeShortAdd":
				writeByte(number >> 8);
				writeByte(number + 128);
				break;
			case "writeShortAddLE":
				writeByte(number + 128);
				writeByte(number >> 8);
				break;
			case "writeInt":
				writeInt(number);
				break;
			case "writeIntLE":
				writeIntLE(number);
				break;
			case "writeIntME":
				writeByte(number >> 8);
				writeByte(number);
				writeByte(number >> 24);
				writeByte(number >> 16);
				break;
			case "writeIntIME":
				writeByte(number >> 16);
				writeByte(number >> 24);
				writeByte(number);
				writeByte(number >> 8);
				break;
			default:
				throw new IOException("unsupported packet write: " + method);
		}
	}

	private void writeByte(int value) throws IOException
	{
		ensure(1);
		payload[offset++] = (byte) value;
	}

	private void writeShort(int value) throws IOException
	{
		writeByte(value >> 8);
		writeByte(value);
	}

	private void writeShortLE(int value) throws IOException
	{
		writeByte(value);
		writeByte(value >> 8);
	}

	private void writeInt(int value) throws IOException
	{
		writeByte(value >> 24);
		writeByte(value >> 16);
		writeByte(value >> 8);
		writeByte(value);
	}

	private void writeIntLE(int value) throws IOException
	{
		writeByte(value);
		writeByte(value >> 8);
		writeByte(value >> 16);
		writeByte(value >> 24);
	}

	private void writeString(String value) throws IOException
	{
		if (value.indexOf('\0') >= 0)
		{
			throw new IOException("packet string contains NUL");
		}
		byte[] bytes = value.getBytes(CP_1252);
		ensure(bytes.length + 1);
		for (byte b : bytes)
		{
			payload[offset++] = b;
		}
		payload[offset++] = 0;
	}

	private void ensure(int length) throws IOException
	{
		if (offset < 0 || offset + length > payload.length)
		{
			throw new IOException("packet payload overflow at " + offset + " of " + payload.length);
		}
	}
}
