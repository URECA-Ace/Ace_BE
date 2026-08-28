package com.ace.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ace.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
