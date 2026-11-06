// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adbd is uid 2000, so its replies to a Tailscale peer leave via eth100.72. This process runs
 * inside the Tailscale uid, whose replies use the tun1 table. Peers connect to the Tailscale
 * address on [PORT] and the bytes are forwarded to local adbd.
 */
object AdbRelay {
  private const val TAG = "AdbRelay"
  const val PORT = 15555
  private const val ADB_PORT = 5555
  private val started = AtomicBoolean(false)

  fun start() {
    if (!started.compareAndSet(false, true)) {
      return
    }
    Thread(::loop, "adb-relay").apply { isDaemon = true }.start()
  }

  private fun loop() {
    var boundIp: String? = null
    var server: ServerSocket? = null
    while (true) {
      val ip = tailscaleIpv4()
      if (ip == null) {
        server = closeServer(server)
        boundIp = null
        sleep(2000)
        continue
      }
      if (server == null || boundIp != ip) {
        server = closeServer(server)
        server =
            try {
              ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ip, PORT))
              }
            } catch (e: IOException) {
              Log.w(TAG, "bind $ip:$PORT failed: $e")
              sleep(2000)
              null
            }
        if (server == null) {
          continue
        }
        boundIp = ip
        Log.i(TAG, "listening $ip:$PORT -> 127.0.0.1:$ADB_PORT")
      }
      try {
        server.soTimeout = 2000
        val client = server.accept()
        Thread({ splice(client) }, "adb-relay-conn").apply { isDaemon = true }.start()
      } catch (_: SocketTimeoutException) {
      } catch (e: IOException) {
        Log.w(TAG, "accept failed: $e")
        server = closeServer(server)
        boundIp = null
        sleep(1000)
      }
    }
  }

  private fun splice(client: Socket) {
    var adb: Socket? = null
    try {
      adb =
          Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress("127.0.0.1", ADB_PORT), 3000)
          }
      client.tcpNoDelay = true
      val fromClient = Thread({ copy(client, adb) }, "adb-relay-c2a")
      val fromAdb = Thread({ copy(adb, client) }, "adb-relay-a2c")
      fromClient.start()
      fromAdb.start()
      fromClient.join()
      fromAdb.join()
    } catch (e: Exception) {
      Log.w(TAG, "relay connection failed: $e")
    } finally {
      closeQuietly(client)
      adb?.let { closeQuietly(it) }
    }
  }

  private fun copy(input: Socket, output: Socket) {
    try {
      val src = input.getInputStream()
      val dst = output.getOutputStream()
      val buf = ByteArray(16 * 1024)
      while (true) {
        val n = src.read(buf)
        if (n < 0) {
          break
        }
        dst.write(buf, 0, n)
        dst.flush()
      }
    } catch (_: IOException) {
    } finally {
      closeQuietly(input)
      closeQuietly(output)
    }
  }

  private fun tailscaleIpv4(): String? {
    val interfaces =
        try {
          NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: IOException) {
          return null
        }
    for (iface in interfaces) {
      if (!iface.name.startsWith("tun")) {
        continue
      }
      for (address in iface.inetAddresses) {
        if (address is Inet4Address && address.hostAddress?.startsWith("100.") == true) {
          val host = address.hostAddress
          if (host != null && host != "100.100.100.100") {
            return host
          }
        }
      }
    }
    return null
  }

  private fun closeServer(server: ServerSocket?): ServerSocket? {
    if (server != null) {
      closeQuietly(server)
    }
    return null
  }

  private fun closeQuietly(socket: Socket) {
    try {
      socket.close()
    } catch (_: IOException) {
    }
  }

  private fun closeQuietly(socket: ServerSocket) {
    try {
      socket.close()
    } catch (_: IOException) {
    }
  }

  private fun sleep(ms: Long) {
    try {
      Thread.sleep(ms)
    } catch (_: InterruptedException) {
    }
  }
}
