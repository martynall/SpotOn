package com.example.spoton

import android.location.Location
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

data class Spot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val userId: String = "",
    var visited: Boolean = false
)

fun addCurrentLocationToFavorites(location: Location) {
    val user = FirebaseAuth.getInstance().currentUser ?: return
    val db = Firebase.firestore
    val spot = Spot(location.latitude, location.longitude, userId = user.uid)
    db.collection("users")
        .document(user.uid)
        .collection("spots")
        .add(spot)
}

fun isNearby(userLocation: Location, spot: Spot, radius: Float = 15f): Boolean {
    val spotLocation = Location("").apply {
        latitude = spot.latitude
        longitude = spot.longitude
    }
    return userLocation.distanceTo(spotLocation) <= radius
}