package com.hotel.workload.profile;

/** The signed-in user's own profile. {@code profileImageUrl} is null when there is no picture (the default avatar is shown). */
public record ProfileDto(String username, String role, String fullName, String profileImageUrl) {}
