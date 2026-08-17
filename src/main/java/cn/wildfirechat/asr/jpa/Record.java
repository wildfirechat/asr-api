package cn.wildfirechat.asr.jpa;

import javax.persistence.*;

@Entity
@Table(name = "record", indexes = {
        @Index(name = "idx_record_appid", columnList = "appId,success"),
        @Index(name = "idx_record_url", columnList = "url")
})
public class Record {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public int id;

    @Column(length = 64)
    public String appId;

    @Column
    public int success;

    @Column
    public int downloadDuration;

    @Column
    public int firstResponseDuration;

    @Column
    public int workDuration;

    @Column
    public int audioDuration;

    @Column(length = 256)
    public String url;

    @Column(length = 4096)
    public String text;

    @Column
    public long receiveTimestamp;
}
