//++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// This file is a part of the 'coroutines' project.
// Copyright 2018 Elmar Sonnenschein, esoco GmbH, Flensburg, Germany
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//	  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
package de.esoco.coroutine.step.nio;

import de.esoco.coroutine.Continuation;
import de.esoco.coroutine.CoroutineException;

import java.io.IOException;

import java.net.SocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;

import java.util.function.BiPredicate;
import java.util.function.Function;


/********************************************************************
 * Implements asynchronous reading from a {@link AsynchronousSocketChannel}.
 *
 * @author eso
 */
public class SocketReceive extends AsynchronousSocketStep
{
	//~ Instance fields --------------------------------------------------------

	private final BiPredicate<Integer, ByteBuffer> pCheckFinished;

	//~ Constructors -----------------------------------------------------------

	/***************************************
	 * Creates a new instance.
	 *
	 * @param fGetSocketAddress A function that provides the target socket
	 *                          address from the current continuation
	 * @param pCheckFinished    A predicate that checks whether receiving is
	 *                          complete by evaluating the byte buffer after
	 *                          reading
	 */
	public SocketReceive(
		Function<Continuation<?>, SocketAddress> fGetSocketAddress,
		BiPredicate<Integer, ByteBuffer>		 pCheckFinished)
	{
		super(fGetSocketAddress);

		this.pCheckFinished = pCheckFinished;
	}

	//~ Static methods ---------------------------------------------------------

	/***************************************
	 * Returns a new predicate to be used with {@link #until(BiPredicate)} that
	 * checks whether a byte buffer contains the complete content of an HTTP
	 * response. The test is performed by calculating the full data size from
	 * the 'Content-Length' attribute in the response header and comparing it
	 * with the buffer position.
	 *
	 * @return A new predicate instance
	 */
	public static BiPredicate<Integer, ByteBuffer> contentFullyRead()
	{
		return new CheckContentLength();
	}

	/***************************************
	 * Suspends until data has been received from a network socket. The data
	 * will be stored in the input {@link ByteBuffer} of the step. If the
	 * capacity of the buffer is reached before the EOF signal is received the
	 * coroutine will be terminated with a {@link CoroutineException}.
	 *
	 * <p>After the data has been fully received {@link ByteBuffer#flip()} will
	 * be invoked on the buffer so that it can be used directly for subsequent
	 * reading from it.</p>
	 *
	 * <p>The returned step only receives the next block of data that is sent by
	 * the remote socket and then continues the coroutine execution. If data
	 * should be read until a certain condition is met a derived step needs to
	 * be created with {@link #until(BiPredicate)}.</p>
	 *
	 * @param  fGetSocketAddress A function that provides the source socket
	 *                           address from the current continuation
	 *
	 * @return A new step instance
	 */
	public static SocketReceive receiveFrom(
		Function<Continuation<?>, SocketAddress> fGetSocketAddress)
	{
		return new SocketReceive(fGetSocketAddress, (r, bb) -> true);
	}

	/***************************************
	 * @see #receiveFrom(Function)
	 */
	public static SocketReceive receiveFrom(SocketAddress rSocketAddress)
	{
		return receiveFrom(c -> rSocketAddress);
	}

	/***************************************
	 * Suspends until data has been received from a previously connected channel
	 * stored in the currently executed coroutine. If no such channel exists the
	 * execution will fail. This invocation is intended to be used for
	 * request-response communication where a receive is always preceded by a
	 * send operation.
	 *
	 * <p>The predicate argument is the same as for the {@link
	 * #until(BiPredicate)} method.</p>
	 *
	 * @param  pCheckFinished A predicate that checks whether the data has been
	 *                        received completely
	 *
	 * @return A new step instance
	 */
	public static SocketReceive receiveUntil(
		BiPredicate<Integer, ByteBuffer> pCheckFinished)
	{
		return receiveFrom((SocketAddress) null).until(pCheckFinished);
	}

	//~ Methods ----------------------------------------------------------------

	/***************************************
	 * Returns a new receive step instance the suspends until data has been
	 * received from a network socket and a certain condition on that data is
	 * met or an end-of-stream signal is received. If the capacity of the buffer
	 * is reached before the receiving is finished the coroutine will fail with
	 * an exception.
	 *
	 * @param  pCheckFinished A predicate that checks whether the data has been
	 *                        received completely
	 *
	 * @return A new step instance
	 */
	public SocketReceive until(BiPredicate<Integer, ByteBuffer> pCheckFinished)
	{
		return new SocketReceive(getSocketAddressFactory(), pCheckFinished);
	}

	/***************************************
	 * {@inheritDoc}
	 */
	@Override
	protected boolean performAsyncOperation(
		int													nBytesReceived,
		AsynchronousSocketChannel							rChannel,
		ByteBuffer											rData,
		ChannelCallback<Integer, AsynchronousSocketChannel> rCallback)
		throws IOException
	{
		boolean bFinished = false;

		if (nBytesReceived >= 0)
		{
			bFinished = pCheckFinished.test(nBytesReceived, rData);
		}

		if (nBytesReceived != -1 && !bFinished && rData.hasRemaining())
		{
			rChannel.read(rData, rData, rCallback);
		}
		else
		{
			checkErrors(rData, nBytesReceived, bFinished);
			rData.flip();
		}

		return bFinished;
	}

	/***************************************
	 * {@inheritDoc}
	 */
	@Override
	protected void performBlockingOperation(
		AsynchronousSocketChannel aChannel,
		ByteBuffer				  rData) throws Exception
	{
		int     nReceived;
		boolean bFinished;

		do
		{
			nReceived = aChannel.read(rData).get();
			bFinished = pCheckFinished.test(nReceived, rData);
		}
		while (nReceived != -1 && !bFinished && rData.hasRemaining());

		checkErrors(rData, nReceived, bFinished);

		rData.flip();
	}

	/***************************************
	 * Checks the received data and throws an exception on errors.
	 *
	 * @param  rData     The received data bytes
	 * @param  nReceived The number of bytes received on the last read
	 * @param  bFinished TRUE if the finish condition is met
	 *
	 * @throws IOException If an error is detected
	 */
	private void checkErrors(ByteBuffer rData, int nReceived, boolean bFinished)
		throws IOException
	{
		if (!bFinished)
		{
			if (nReceived == -1)
			{
				throw new IOException("Received data incomplete");
			}
			else if (!rData.hasRemaining())
			{
				throw new IOException("Buffer capacity exceeded");
			}
		}
	}

	//~ Inner Classes ----------------------------------------------------------

	/********************************************************************
	 * A predicate to check the Content-Length property in an HTTP header.
	 *
	 * @author eso
	 */
	static class CheckContentLength implements BiPredicate<Integer, ByteBuffer>
	{
		//~ Static fields/initializers -----------------------------------------

		private static final String CONTENT_LENGTH_HEADER = "Content-Length: ";

		//~ Instance fields ----------------------------------------------------

		private int nFullLength = -1;

		//~ Methods ------------------------------------------------------------

		/***************************************
		 * {@inheritDoc}
		 */
		@Override
		public boolean test(Integer nReceived, ByteBuffer rBuffer)
		{
			if (nFullLength == -1)
			{
				String sData =
					StandardCharsets.UTF_8.decode(
						rBuffer.duplicate().flip()).toString();

				int nLengthPos = sData.indexOf(CONTENT_LENGTH_HEADER);

				nFullLength = sData.indexOf("\r\n\r\n");

				if (nFullLength == -1)
				{
					throw new IllegalArgumentException("No HTTP header found");
				}

				if (nLengthPos == -1)
				{
					throw new IllegalArgumentException(
						"No content length found");
				}

				int nContentLength =
					Integer.parseInt(
						sData.substring(
							nLengthPos + CONTENT_LENGTH_HEADER.length(),
							sData.indexOf("\r\n", nLengthPos)));

				nFullLength += nContentLength + 4; // 4 = CRLFCRLF
			}

			return rBuffer.position() >= nFullLength;
		}
	}
}
