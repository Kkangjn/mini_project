package com.alttalttal.mini_project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Zzim")
@Getter
@NoArgsConstructor
public class Zzim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long recipeId;
    @Column
    private Long userId;

    public Zzim(Long recipeId, Long userId) {
        this.recipeId = recipeId;
        this.userId = userId;
    }
}
