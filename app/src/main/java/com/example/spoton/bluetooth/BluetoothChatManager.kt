package com.example.spoton.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.util.Log

const val MESSAGE_READ = 1
const val MESSAGE_WRITE = 2
const val MESSAGE_DEVICE_NAME = 3
const val MESSAGE_TOAST = 4

const val APP_NAME = "spoton"

//class BluetoothChatManager(private val context: Context, private val handler: Handler)
@SuppressLint("MissingPermission")
class BluetoothChatManager(private val context: Context) {
    val MY_UUID: UUID = UUID.fromString("e483c180-b9e2-4a57-a761-cbdc90b69b54")
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices

    private val _isDiscoveryFinished = MutableStateFlow(false)
    val isDiscoveryFinished: StateFlow<Boolean> = _isDiscoveryFinished

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private var oldName: String? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val deviceClass = it.bluetoothClass?.deviceClass
                        if (device != null && !device.name.isNullOrBlank()) {
                            _foundDevices.update { currentList ->
                                if (!currentList.any { d -> d.address == it.address }) {
                                    currentList + it
                                } else currentList
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscoveryFinished.value = true

                    val pairedDevices = bluetoothAdapter?.bondedDevices?.filter {
                        !it.name.isNullOrBlank() && !_foundDevices.value.any { d -> d.address == it.address }
                    } ?: emptyList()

                    if (pairedDevices.isNotEmpty()) {
                        _foundDevices.update { it + pairedDevices }
                    }

                    val devices = _foundDevices.value
                    if (devices.isEmpty()) {
                        Toast.makeText(context, "Nie znaleziono żadnych urządzeń", Toast.LENGTH_SHORT).show()
                    }
                    context?.unregisterReceiver(this)
                }
            }
        }
    }


    init {
        // Inicjalizacja adaptera Bluetooth
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    // Klasy stanów połączenia
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Listening : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private var uidSent = false

    private fun sendOwnUidIfNeeded() {
        if (!uidSent) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                writeMessage("UID:$uid")
                Log.d("Bluetoothchatt", "Wysłano UID: $uid")
                mHandler.obtainMessage(MESSAGE_TOAST, Bundle().apply { putString("toast", "Twój UID został wysłany") }).sendToTarget()
                uidSent = true
            }
        }
    }

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        uidSent = false

        connectedThread = ConnectedThread(socket) // Tutaj tworzysz instancję ConnectedThread
        connectedThread?.start()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        sendOwnUidIfNeeded()

        val msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME)
        val bundle = Bundle()
        bundle.putString("device_name", device.name)
        msg.data = bundle
        mHandler.sendMessage(msg)
    }

    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID)
        }
        override fun run() {
            var socket: BluetoothSocket? = null
            try {
                Log.d("Bluetoothchat", "Serwer oczekuje na połączenie...")
                socket = mmServerSocket?.accept()
                Log.d("Bluetoothchat", "Serwer zaakceptował połączenie")
                socket?.let { s ->
                    val deviceName = s.remoteDevice.name ?: s.remoteDevice.address
                    Handler(Looper.getMainLooper()).post {
                        // Wyświetl dialog z potwierdzeniem
                        android.app.AlertDialog.Builder(context)
                            .setTitle("Nowe połączenie Bluetooth")
                            .setMessage("Połączyć z urządzeniem: $deviceName?")
                            .setPositiveButton("Tak") { _, _ ->
                                manageConnectedSocket(s, s.remoteDevice)
                                Log.d("Bluetoothchat", "AcceptThread: Połączenie zaakceptowane z urządzeniem: ${s.remoteDevice.name ?: s.remoteDevice.address}")
                            }
                            .setNegativeButton("Nie") { _, _ ->
                                try { s.close() } catch (_: IOException) {}
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            } catch (e: IOException) {
                Log.e("Bluetooth", "Błąd serwera: ${e.message}", e)
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) { /* Logowanie błędu */ }
        }
    }
    private inner class ConnectThread(private val device: BluetoothDevice, private val uuid: UUID) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        }

        override fun run() {
            bluetoothAdapter?.cancelDiscovery() // Zawsze anuluj discovery

            try {
                mmSocket?.connect()
            } catch (e: IOException) {
                try { mmSocket?.close() } catch (e2: IOException) { /* Logowanie błędu */ }
                mHandler.obtainMessage(MESSAGE_TOAST, Bundle().apply { putString("toast", "Twój opis błędu") }).sendToTarget()
                _connectionState.value = ConnectionState.Disconnected
                return
            }
            mmSocket?.let {
                manageConnectedSocket(it, device)
                Log.d("Bluetoothchat", "ConnectThread: Połączono z urządzeniem: ${device.name ?: device.address}")
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) { /* Logowanie błędu */ }
        }
    }
    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            var numBytes: Int
            while (true) {
                try {
                    numBytes = mmInStream.read(mmBuffer)
                    mHandler.obtainMessage(MESSAGE_READ, numBytes, -1, mmBuffer).sendToTarget()
                } catch (e: IOException) {
                    mHandler.obtainMessage(MESSAGE_TOAST, Bundle().apply { putString("toast", "Połączenie utracone: ${e.message}") }).sendToTarget()
                    _connectionState.value = ConnectionState.Disconnected
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                mHandler.obtainMessage(MESSAGE_WRITE, -1, -1, bytes).sendToTarget()
            } catch (e: IOException) {
                mHandler.obtainMessage(MESSAGE_TOAST, Bundle().apply { putString("toast", "Błąd podczas wysyłania: ${e.message}") }).sendToTarget()
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) { /* Logowanie błędu */ }
        }
    }



    // Klasa do reprezentowania wiadomości czatu
    data class ChatMessage(val text: String, val isLocalUser: Boolean)

    // Handler do odbierania wiadomości z wątków Bluetooth (jak wcześniej)
    // Pamiętaj, że ten handler musi być tworzony w głównym wątku (Looper.getMainLooper())
    // i przekazywany do ConnectedThread.
    // Wewnątrz handlera będziesz aktualizować _messages i _connectionState
    private val mHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MESSAGE_READ -> {
                val readBuf = msg.obj as ByteArray
                val readMessage = String(readBuf, 0, msg.arg1)
                if (readMessage.startsWith("UID:")) {
                    val foreignUid = readMessage.removePrefix("UID:").trim()
                    Log.d("Bluetoothchat", "Odebrano UID od urządzenia: $foreignUid")
                    fetchAndSaveForeignSpots(context, foreignUid)
                    sendOwnUidIfNeeded()
                } else {
                    _messages.update { it + ChatMessage(readMessage, isLocalUser = false) }
                }
            }
            MESSAGE_WRITE -> { /* ... */ }
            MESSAGE_DEVICE_NAME -> {
                val deviceName = msg.data.getString("device_name")
                if (deviceName != null) {
                    _connectionState.value = ConnectionState.Connected(deviceName)
                }
                //Log.d("Bluetooth", "Połączono z urządzeniem: $deviceName")
            }
            MESSAGE_TOAST -> {
                val toastMessage = msg.data.getString("toast") ?: "Nieznany błąd Bluetooth"
                Log.e("Bluetoothchat", "TOAST: $toastMessage")
                _connectionState.value = ConnectionState.Error(toastMessage)
            }
        }
        true
    }

    fun setupBluetooth(): Boolean {
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth nie jest obsługiwany.")
            return false
        }
        if (!bluetoothAdapter!!.isEnabled) {
            // W Compose nie możesz użyć startActivityForResult bezpośrednio z ViewModelu/Managera.
            // Będziesz musiał to obsłużyć w Activity lub w composable za pomocą ActivityResultLauncher.
            return false // Wymaga akcji użytkownika
        }
        return true
    }

    // Metody do zarządzania połączeniem (startServer, startClient, connect, write, stop)
    // Tutaj umieścisz logikę z AccessThread, ConnectThread, ConnectedThread.
    // Pamiętaj, aby przekazywać mHandler do ConnectedThread!

    fun startServer() {
        // Uruchom AcceptThread
        if (acceptThread == null) {
            acceptThread = AcceptThread() // Przekaż handler
            acceptThread?.start()
            _connectionState.value = ConnectionState.Listening
        }
        // Uczyń urządzenie widocznym (wymaga Intent z Activity)
    }

    fun startClient() {
        _isDiscoveryFinished.value = false
        bluetoothAdapter?.cancelDiscovery()
        _foundDevices.value = emptyList()
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        context.registerReceiver(discoveryReceiver, filter)
        bluetoothAdapter?.startDiscovery()
        _connectionState.value = ConnectionState.Connecting
    }

    fun connectToDevice(device: BluetoothDevice) {
        // Anuluj discovery jeśli trwa
        bluetoothAdapter?.cancelDiscovery()
        // Uruchom ConnectThread
        connectThread?.cancel()
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // Rozpocznij parowanie
            device.createBond()
            // Nasłuchuj na zakończenie parowania
            val bondReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action
                    val bondedDevice: BluetoothDevice? = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && bondedDevice?.address == device.address) {
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            context?.unregisterReceiver(this)
                            // Po sparowaniu uruchom ConnectThread
                            connectThread = ConnectThread(device, MY_UUID)
                            connectThread?.start()
                            _connectionState.value = ConnectionState.Connecting
                        } else if (bondState == BluetoothDevice.BOND_NONE) {
                            context?.unregisterReceiver(this)
                            _connectionState.value = ConnectionState.Error("Parowanie nie powiodło się")
                        }
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondReceiver, filter)
        } else {
            // Już sparowane, łącz od razu
            connectThread = ConnectThread(device, MY_UUID)
            connectThread?.start()
            _connectionState.value = ConnectionState.Connecting
        }
    }

    fun writeMessage(message: String) {
        connectedThread?.write(message.toByteArray())
        _messages.update { it + ChatMessage(message, isLocalUser = true) }
    }

    fun stop() {
        // Zatrzymaj wszystkie wątki i ustaw stan na Disconnected
        bluetoothAdapter?.name = oldName
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun sendOwnUid() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            writeMessage("UID:$uid")
        }
        Toast.makeText(context, "Coś się wydarzyło", Toast.LENGTH_SHORT).show()
    }

    // ... Wewnętrzne klasy wątków (AcceptThread, ConnectThread, ConnectedThread) ...
    // Pamiętaj, aby w ConnectedThread przekazywać dane do Handlera:
    // mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget();
}

private fun fetchAndSaveForeignSpots(context: Context, foreignUid: String) {
    val db = Firebase.firestore
    val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

    Log.d("Bluetoothchat", "Wywołano fetchAndSaveForeignSpots dla foreignUid=$foreignUid, myUid=$myUid")

    db.collection("users").document(foreignUid).collection("spots")
        .whereEqualTo("userId", foreignUid)
        .get()
        .addOnSuccessListener { result ->
            val spots = result.documents.mapNotNull { it.data }
            Log.d("Bluetoothchat", "Pobrano ${spots.size} spotów od foreignUid=$foreignUid")
            spots.forEach { spotData ->
                Log.d("Bluetoothchat", "Dodaję spot: $spotData")
                db.collection("users").document(myUid).collection("spots")
                    .add(spotData)
            }
            Toast.makeText(context, "Punkty zostały przesłane!", Toast.LENGTH_SHORT).show()
        }
        .addOnFailureListener { e ->
            Log.e("Bluetoothchat", "Błąd pobierania spotów: ${e.message}", e)
        }
}