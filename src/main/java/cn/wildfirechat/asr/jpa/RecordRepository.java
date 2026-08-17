package cn.wildfirechat.asr.jpa;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface RecordRepository extends CrudRepository<Record, Integer> {
    List<Record> findByUrl(String url);
}
