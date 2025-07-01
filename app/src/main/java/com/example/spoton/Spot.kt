package com.example.spoton

import android.location.Location
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

data class Spot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val name: String = ""
)

fun addCurrentLocationToFavorites(location: Location, name: String = "Ulubione miejsce") {
    val user = FirebaseAuth.getInstance().currentUser ?: return
    val db = Firebase.firestore
    val spot = Spot(location.latitude, location.longitude, name)
    db.collection("users")
        .document(user.uid)
        .collection("spots")
        .add(spot)
}