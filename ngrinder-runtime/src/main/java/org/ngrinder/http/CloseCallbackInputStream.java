/*
 * Copyright (c) 2012-present NAVER Corp.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at https://naver.github.io/ngrinder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

public class CloseCallbackInputStream extends InputStream {
	private final InputStream delegate;
	private final Runnable callback;

	private CloseCallbackInputStream(InputStream delegate, Runnable callback) {
		this.delegate = delegate;
		this.callback = callback;
	}

	public static CloseCallbackInputStream of(InputStream inputStream, Runnable callback) {
		return new CloseCallbackInputStream(inputStream, callback);
	}

	@Override
	public int read() throws IOException {
		return delegate.read();
	}

	@Override
	public int read(@NotNull byte[] b) throws IOException {
		return delegate.read(b);
	}

	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException {
		return delegate.read(b, off, len);
	}

	@Override
	public long skip(long n) throws IOException {
		return delegate.skip(n);
	}

	@Override
	public int available() throws IOException {
		return delegate.available();
	}

	@Override
	public void close() throws IOException {
		callback.run();
		delegate.close();
	}

	@Override
	public synchronized void mark(int readlimit) {
		delegate.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		delegate.reset();
	}

	@Override
	public boolean markSupported() {
		return delegate.markSupported();
	}
}
