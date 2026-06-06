create table if not exists projects (
    id bigint primary key auto_increment,
    project_uid varchar(40) null,
    title varchar(120) not null,
    status varchar(40) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    unique key uk_projects_project_uid (project_uid)
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

create table if not exists story_entities (
    id bigint primary key auto_increment,
    project_id bigint not null,
    entity_id varchar(40) not null,
    entity_type varchar(40) not null,
    canonical_name varchar(120) not null,
    aliases_json text not null,
    profile text null,
    source_refs_json text not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint fk_story_entities_project
        foreign key (project_id) references projects(id)
        on delete cascade,
    unique key uk_story_entities_project_entity (project_id, entity_id),
    index idx_story_entities_project_type (project_id, entity_type)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists story_events (
    id bigint primary key auto_increment,
    project_id bigint not null,
    event_id varchar(40) not null,
    chapter_id bigint not null,
    event_order int not null,
    title varchar(200) not null,
    summary text not null,
    source_refs_json text not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    constraint fk_story_events_project
        foreign key (project_id) references projects(id)
        on delete cascade,
    constraint fk_story_events_chapter
        foreign key (chapter_id) references source_chapters(id)
        on delete cascade,
    unique key uk_story_events_project_event (project_id, event_id),
    index idx_story_events_project_order (project_id, event_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
