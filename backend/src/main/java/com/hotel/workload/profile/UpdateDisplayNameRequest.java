package com.hotel.workload.profile;

/** The only thing the client sends: the new name. Who is changed comes from the token, never from the request. */
public record UpdateDisplayNameRequest(String displayName) {}
