package com.microsoft.azure.samples.functions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.fileupload.MultipartStream;

/**
 * A utility for wrapping a {@link MultipartStream} in a stream of parts ({@link FormPart}).
 * @author yevster
 *
 */
public class PartStreamFactory {
	
	/**
	 * A value object containing the header and body of a part of a multipart form.
	 */
	public static final class FormPart {
		private final byte[] body;
		private final String header;

		public FormPart(byte[] body, String header) {
			this.body = body;
			this.header = header;
		}

		public byte[] getBody() {
			return body;
		}

		public String getHeader() {
			return header;
		}
	}

	/**
	 * An {@link Iterator} of form parts used internally to generate a stream for cleaner consumption.
	 */
	private static class FormPartIterator implements Iterator<FormPart> {
		private final MultipartStream multipartStream;
		private boolean hasNext = false;

		public FormPartIterator(MultipartStream multipartStream) {
			this.multipartStream = multipartStream;
			try {
				hasNext = this.multipartStream.skipPreamble();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public boolean hasNext() {
			return hasNext;
		}

		@Override
		public FormPart next() {
			try {
				String header = multipartStream.readHeaders();
				ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
				multipartStream.readBodyData(baos);
				byte[] fileBody = baos.toByteArray();
				hasNext = multipartStream.readBoundary();
				return new FormPart(fileBody, header);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Wraps a {@link MultipartStream} as a Java stream of parts, for idiomatic consumption.
	 * @param stream A MultipartStream in its initial position.
	 * @return A stream of form parts/regions.
	 */
	public static Stream<FormPart> asStream(MultipartStream stream) {
		Spliterator<FormPart> spliterator = Spliterators.spliteratorUnknownSize(new FormPartIterator(stream),
				Spliterator.IMMUTABLE + Spliterator.ORDERED);
		return StreamSupport.stream(spliterator, false);
	}
}
