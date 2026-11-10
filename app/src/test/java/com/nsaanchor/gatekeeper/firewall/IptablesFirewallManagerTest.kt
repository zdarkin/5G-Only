package com.nsaanchor.gatekeeper.firewall

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.nsaanchor.gatekeeper.network.TelephonyNsaMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptablesFirewallManagerTest {

    @Test
    fun `buildLockdownScript generates exact NSA_LOCKDOWN chain and interface hooks`() {
        val script = IptablesFirewallManager.buildLockdownScript(
            heartbeatIp = "1.1.1.1",
            interfaces = listOf("rmnet+", "ccmni+"),
            blockIpv6 = true,
            appUid = 10245
        )

        assertTrue(script.contains("iptables -w -N NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -A NSA_LOCKDOWN -d 1.1.1.1 -j ACCEPT"))
        assertTrue(script.contains("iptables -w -A NSA_LOCKDOWN -j DROP"))
        assertTrue(script.contains("-I OUTPUT 1 -o rmnet+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("-I FORWARD 1 -o rmnet+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("-I OUTPUT 1 -o ccmni+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("-I FORWARD 1 -o ccmni+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("ip6tables -w -N NSA_LOCKDOWN"))
        assertTrue(script.contains("--uid-owner 10245 -j ACCEPT"))
    }

    @Test
    fun `buildCleanupScript removes OUTPUT and FORWARD hooks then flushes and deletes chain`() {
        val script = IptablesFirewallManager.buildCleanupScript(
            interfaces = listOf("rmnet+", "ccmni+")
        )

        assertTrue(script.contains("iptables -w -D OUTPUT -o rmnet+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -D FORWARD -o rmnet+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -D OUTPUT -o ccmni+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -D FORWARD -o ccmni+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -F NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -X NSA_LOCKDOWN"))
    }

    @Test
    fun `invalid heartbeat IP falls back to 1_1_1_1 to prevent command injection`() {
        val maliciousInput = "1.1.1.1; rm -rf / #"
        assertFalse(IptablesFirewallManager.isValidIpv4(maliciousInput))
        assertEquals("1.1.1.1", IptablesFirewallManager.sanitizeHeartbeatIp(maliciousInput))

        val script = IptablesFirewallManager.buildLockdownScript(
            heartbeatIp = maliciousInput,
            interfaces = listOf("rmnet+"),
            blockIpv6 = false
        )
        assertTrue(script.contains("-d 1.1.1.1 -j ACCEPT"))
        assertFalse(script.contains("rm -rf"))
    }

    @Test
    fun `invalid interface names are rejected against injection`() {
        assertTrue(IptablesFirewallManager.isValidInterfaceName("rmnet+"))
        assertTrue(IptablesFirewallManager.isValidInterfaceName("ccmni+"))
        assertTrue(IptablesFirewallManager.isValidInterfaceName("v4-rmnet_data0"))
        assertFalse(IptablesFirewallManager.isValidInterfaceName("rmnet+; reboot"))
        assertFalse(IptablesFirewallManager.isValidInterfaceName(""))
    }

    @Test
    fun `buildLockdownScript cleans up stale interfaces and unhooks ipv6 when blockIpv6 is false`() {
        val script = IptablesFirewallManager.buildLockdownScript(
            heartbeatIp = "8.8.8.8",
            interfaces = listOf("rmnet+"),
            blockIpv6 = false,
            staleInterfaces = listOf("pdp_ip+")
        )

        assertTrue(script.contains("iptables -w -D OUTPUT -o pdp_ip+ -j NSA_LOCKDOWN"))
        assertTrue(script.contains("iptables -w -A NSA_LOCKDOWN -d 8.8.8.8 -j ACCEPT"))
        assertTrue(script.contains("ip6tables -w -F NSA_LOCKDOWN"))
        assertTrue(script.contains("ip6tables -w -X NSA_LOCKDOWN"))
    }

    @Test
    fun `evaluateSnapshot keeps traffic blocked when override is NR_NSA but nrState is NOT_RESTRICTED`() {
        val snapshot = TelephonyNsaMonitor.evaluateSnapshot(
            simSlotIndex = 0,
            subscriptionId = 1,
            networkType = TelephonyManager.NETWORK_TYPE_LTE,
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            nrState = "NOT_RESTRICTED"
        )

        assertFalse(snapshot.is5gNsaOrBetter)
        assertTrue(snapshot.isLteOnly)
        assertEquals("NOT_RESTRICTED", snapshot.nrState)
    }

    @Test
    fun `evaluateSnapshot unblocks traffic when LTE anchor has nrState CONNECTED`() {
        val snapshot = TelephonyNsaMonitor.evaluateSnapshot(
            simSlotIndex = 1,
            subscriptionId = 2,
            networkType = TelephonyManager.NETWORK_TYPE_LTE,
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            nrState = "CONNECTED"
        )

        assertTrue(snapshot.is5gNsaOrBetter)
        assertFalse(snapshot.isLteOnly)
        assertEquals("CONNECTED", snapshot.nrState)
        assertEquals(1, snapshot.simSlotIndex)
    }

    @Test
    fun `evaluateSnapshot unblocks traffic on 5G SA NETWORK_TYPE_NR`() {
        val snapshot = TelephonyNsaMonitor.evaluateSnapshot(
            simSlotIndex = 0,
            subscriptionId = 1,
            networkType = TelephonyManager.NETWORK_TYPE_NR,
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
            nrState = "NONE"
        )

        assertTrue(snapshot.is5gNsaOrBetter)
        assertFalse(snapshot.isLteOnly)
    }

    @Test
    fun `extractNrStateFromText picks CONNECTED when CS has nrState NONE and PS WWAN has nrState CONNECTED`() {
        val sampleServiceState = """
            ServiceState: {mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE),
            NetworkRegistrationInfo{ domain=CS transportType=WWAN registrationState=HOME nrState=NONE },
            NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=HOME nrState=CONNECTED }}
        """.trimIndent()

        assertEquals("CONNECTED", TelephonyNsaMonitor.extractNrStateFromText(sampleServiceState))
    }

    @Test
    fun `extractNrStateFromDumpsys isolates SIM 1 and SIM 2 without cross-slot contamination`() {
        val sampleDumpsys = """
            Phone Id=0
              mServiceState={mVoiceRegState=0(IN_SERVICE), NetworkRegistrationInfo{ domain=PS nrState=CONNECTED }}
            Phone Id=1
              mServiceState={mVoiceRegState=0(IN_SERVICE), NetworkRegistrationInfo{ domain=PS nrState=NONE }}
        """.trimIndent()

        assertEquals("CONNECTED", TelephonyNsaMonitor.extractNrStateFromDumpsys(sampleDumpsys, simSlotIndex = 0))
        assertEquals("NONE", TelephonyNsaMonitor.extractNrStateFromDumpsys(sampleDumpsys, simSlotIndex = 1))
    }
}
