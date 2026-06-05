create table if not exists projects (
    id bigint primary key auto_increment,
    title varchar(120) not null,
    status varchar(40) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists source_chapters (
    id bigint primary key auto_increment,
    project_id bigint not null,
    chapter_no int not null,
    title varchar(200) not null,
    raw_text longtext not null,
    clean_text longtext not null,
    summary text null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_chapters_project
        foreign key (project_id) references projects(id)
        on delete cascade,
    index idx_source_chapters_project_no (project_id, chapter_no)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
